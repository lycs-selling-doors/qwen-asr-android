package dev.workspace.qwen.asr;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class Inference implements AutoCloseable {
    record Config(String asrUrl, String translationUrl, String translationModel, String apiKey,
                  String source, String target, String translationMode, String modelId,
                  NativeRuntime.Tuning tuning) {}

    static final String MODEL = "model.gguf";
    static final String PROJECTOR = "mmproj.gguf";
    interface Control extends Runnable {
        default void recovering(boolean active) {}
    }
    record Segment(String entry, long sample) {}
    private record Recognition(String text, boolean truncated, long elapsedMillis) {}
    private static final Control UNTRACKED = () -> {};
    private static final int CONSECUTIVE_OVERRUNS = 3;

    private static final class Session {
        final String url;
        final Process process;
        final boolean niceApplied;
        volatile int layers;
        volatile int totalLayers;
        volatile String allocated = "";

        Session(String url, Process process, boolean niceApplied) {
            this.url = url;
            this.process = process;
            this.niceApplied = niceApplied;
        }
    }

    private static final class SegmentRecovery {
        boolean reloaded;
    }

    private final Context context;
    private final Config config;
    private final Models.Pair pair;
    private final String engineKey;
    private final Translator translator;
    private final String modelId;
    private final NativeRuntime.Tuning tuning;
    private final PerformanceLimit.Preset nativePreset;
    private final PerformanceLimit.NativeSetup nativeSetup;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final InferenceScheduler scheduler = new InferenceScheduler();
    private final RtfWatchdog watchdog = new RtfWatchdog();
    private final PerformanceLimit performanceLimit = new PerformanceLimit();
    private volatile Session session;
    private volatile FutureTask<Void> recovery;
    private volatile boolean retrying;
    private volatile boolean processing;
    private volatile boolean closed;
    private static final java.util.regex.Pattern LAYERS = java.util.regex.Pattern.compile("offloaded (\\d{1,4})/(\\d{1,4}) layers");
    private static final java.util.regex.Pattern BUFFER = java.util.regex.Pattern.compile(
            "\\b(" + NativeRuntime.BACKEND + ")\\s+model buffer", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern MEMORY_BUFFER = java.util.regex.Pattern.compile(
            "\\b(HTP\\d+(?:-HOST)?|CPU)\\s+(model|KV|compute) buffer size\\s*=\\s*(\\d{1,8}(?:\\.\\d{1,5})?)\\s+MiB");
    private static final java.util.regex.Pattern TIMING = java.util.regex.Pattern.compile(
            "(prompt eval time|eval time|total time)\\s*=\\s*(\\d{1,9}(?:\\.\\d{1,5})?)\\s*ms");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false).connectTimeout(5, TimeUnit.SECONDS).build();

    Inference(Context context, Config config) throws IOException {
        this.context = context.getApplicationContext();
        this.config = config;
        if (!config.asrUrl().isBlank()) {
            engineKey = "";
            pair = null;
            modelId = "external";
            tuning = config.tuning();
            nativePreset = null;
            nativeSetup = null;
            session = new Session(baseUrl(config.asrUrl()), null, false);
        } else {
            pair = new Models(context).selected(config.modelId());
            if (pair == null) throw new IOException("请先安装主模型与匹配的 mmproj");
            modelId = pair.card().id();
            nativePreset = PerformanceLimit.selected(context);
            var resolved = resolveTuning(context, modelId, config.tuning(), nativePreset);
            nativeSetup = nativePreset.configure(resolved);
            tuning = nativeSetup.tuning();
            engineKey = newPassword();
            startSession();
        }
        Translator next = null;
        try {
            next = switch (config.translationMode()) {
                case "offline" -> new Translator.Offline();
                case "online" -> new Translator.Online(config.translationUrl(), config.apiKey(), config.translationModel());
                default -> null;
            };
        } catch (RuntimeException error) {
            if (session.process != null) session.process.destroyForcibly();
            throw error;
        }
        translator = next;
        log("INFO", "加载引擎 " + (pair == null ? "外部" : tuning.device()));
    }

    static NativeRuntime.Tuning resolveTuning(Context context, String modelId, NativeRuntime.Tuning requested,
                                              PerformanceLimit.Preset preset) {
        if (NativeRuntime.cpuOnly()) {
            return new NativeRuntime.Tuning("CPU", requested.threads(), requested.batch(),
                    requested.context(), requested.output(), 0);
        }
        if (!requested.device().equals("auto")) return requested;
        var htp = new NativeRuntime.Tuning("HTP0", requested.threads(), requested.batch(),
                requested.context(), requested.output(), requested.layers());
        if (NativeRuntime.verified(context, modelId, htp, preset)) return htp;
        return new NativeRuntime.Tuning("CPU", requested.threads(), requested.batch(),
                requested.context(), requested.output(), requested.layers());
    }

    private synchronized Session startSession() throws IOException {
        if (closed) throw new RecoveryError("推理服务已停止");
        NativeRuntime.Started started = NativeRuntime.start(context, pair, nativeSetup, engineKey);
        log("INFO", String.format(Locale.ROOT,
                "%s · %s · 线程 %d/%d · -b %d · -ub %d · -c %d · -n %d · cache-ram %d MiB · %s",
                nativePreset.label, tuning.device(), tuning.threads(), tuning.threads(), tuning.batch(),
                nativeSetup.microBatch(), tuning.context(), tuning.output(), NativeRuntime.PROMPT_CACHE_MIB,
                started.lowerPriority() ? "nice +" + nativeSetup.niceAdjustment() : "继承系统优先级"));
        if (nativeSetup.niceAdjustment() > 0 && !started.lowerPriority()) {
            log("WARN", "系统未能降低推理进程优先级，线程和批大小限制已启用");
        }
        Session next = new Session("http://127.0.0.1:" + started.port(), started.process(), started.lowerPriority());
        session = next;
        new Thread(() -> readLogs(next), "llama-server logs").start();
        return next;
    }

    private void readLogs(Session current) {
        try (var logs = new java.io.BufferedReader(new java.io.InputStreamReader(
                current.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = logs.readLine()) != null) {
                var offload = LAYERS.matcher(line);
                var buffer = BUFFER.matcher(line);
                var timing = TIMING.matcher(line);
                var memory = MEMORY_BUFFER.matcher(line);
                boolean reportedMemory = memory.find();
                if (offload.find()) {
                    current.layers = Integer.parseInt(offload.group(1));
                    current.totalLayers = Integer.parseInt(offload.group(2));
                    log("INFO", "语言模型卸载 " + current.layers + "/" + current.totalLayers + " 层");
                } else if (buffer.find()) {
                    current.allocated = buffer.group(1);
                    if (!reportedMemory) log("INFO", "语言模型缓冲区 " + current.allocated);
                } else if (timing.find()) {
                    log("DEBUG", timing.group(1) + " " + timing.group(2) + " ms");
                } else if (line.contains("failed to load") || line.contains("error loading model")) {
                    log("ERROR", "llama.cpp 加载失败，请核对设备与配套模型");
                }
                if (reportedMemory) {
                    log("INFO", "内存缓冲 · " + memory.group(1) + " " + memory.group(2) + " " + memory.group(3) + " MiB");
                }
            }
        } catch (IOException ignored) {}
    }

    private void log(String level, String message) {
        RuntimeLog.add(level, "llama.cpp", run, modelId, message);
    }

    String modelId() { return modelId; }
    PerformanceLimit.NativeSetup nativeSetup() { return nativeSetup; }
    int pending() { return scheduler.pending(); }
    boolean recovering() {
        FutureTask<Void> task = recovery;
        return retrying || task != null && !task.isDone();
    }

    boolean alive() {
        Session current = session;
        return !closed && (pair == null || processing || recovering() || current.process.isAlive());
    }

    boolean waitingForPerformanceLimit() { return performanceLimit.waiting(); }

    PerformanceLimit.Status performanceStatus() {
        return PerformanceLimit.status(pair != null, PerformanceLimit.selected(context), nativePreset,
                nativeSetup, session.niceApplied);
    }

    RtfWatchdog.Status rtfStatus() {
        String device = pair == null ? "external" : tuning.device();
        var settings = RtfWatchdog.read(context, modelId, device);
        var limit = watchdog.limit(settings, device);
        return new RtfWatchdog.Status(modelId, device, settings, limit.rtf(), limit.overheadSeconds());
    }

    String backend() {
        Session current = session;
        if (pair == null) return "外部引擎 · 设备未报告";
        if (NativeRuntime.cpuOnly()) return "ARM64 CPU";
        if (current.layers > 0 && !current.allocated.isBlank()) {
            return "CPU 音频 · " + current.allocated + " " + current.layers + "/" + current.totalLayers
                    + " 层" + (current.layers < current.totalLayers ? " · 混合执行" : "");
        }
        return "CPU 音频 · CPU 文本";
    }

    void awaitReady() throws Exception { awaitReady(session); }

    private void awaitReady(Session current) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + (pair == null ? 10_000 : 180_000);
        do {
            if (current.process != null && !current.process.isAlive()) {
                throw new IOException("llama-server 提前退出，退出码 " + current.process.exitValue()
                        + "。请查看 QwenLlama 日志。");
            }
            try {
                request(current.url + "/health", engineKey, null, null, 3000);
                if (pair != null && !tuning.device().equals("CPU") && (current.layers == 0
                        || !(current.allocated.equalsIgnoreCase(tuning.device())
                        || current.allocated.startsWith(tuning.device() + ":")))) {
                    throw new IllegalStateException("未验证 " + tuning.device() + " 文本卸载。请切换 CPU 或重新探测。");
                }
                log("INFO", backend());
                return;
            } catch (IOException error) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw error;
                Thread.sleep(750);
            }
        } while (!Thread.currentThread().isInterrupted());
        throw new InterruptedException();
    }

    String transcribe(byte[] pcm) throws Exception { return transcribe(pcm, config.source(), false); }

    String transcribe(byte[] pcm, String language, boolean live) throws Exception {
        return scheduler.run(live, () -> {
            awaitRecovery(UNTRACKED);
            Recognition result = recognize(session, pcm, language, UNTRACKED, false);
            logRtf(result.elapsedMillis(), RtfWatchdog.audioSeconds(pcm));
            return result.text();
        });
    }

    String transcribe(byte[] pcm, String language, Segment segment, Control control) throws Exception {
        return scheduler.run(false, () -> {
            processing = true;
            try {
                return recognizeSegment(pcm, language, segment, control, new SegmentRecovery());
            } finally {
                retrying = false;
                processing = false;
                control.recovering(false);
            }
        });
    }

    private String recognizeSegment(byte[] pcm, String language, Segment segment,
                                    Control control, SegmentRecovery state) throws Exception {
        double audioSeconds = RtfWatchdog.audioSeconds(pcm);
        double[] overruns = new double[CONSECUTIVE_OVERRUNS];
        int consecutive = 0;
        long firstExceeded = 0;
        RtfWatchdog.Settings previous = null;
        while (true) {
            control.run();
            awaitRecovery(control);
            var settings = RtfWatchdog.read(context, modelId, tuning.device());
            if (!settings.equals(previous)) consecutive = 0;
            previous = settings;
            var limit = watchdog.limit(settings, tuning.device());
            Session current = session;
            Recognition result;
            try {
                result = recognize(current, pcm, language, control, true);
            } catch (IOException error) {
                control.run();
                if (pair == null || !(error instanceof InterruptedIOException || !current.process.isAlive())) {
                    throw error;
                }
                reloadForSegment(state, segment, control, "本地运行时超时或进程退出");
                consecutive = 0;
                continue;
            }
            double elapsedSeconds = logRtf(result.elapsedMillis(), audioSeconds);
            double rtf = elapsedSeconds / audioSeconds;
            control.run();
            boolean overLimit = pair != null && limit.exceeded(audioSeconds, elapsedSeconds);
            if (settings.enabled() && overLimit) {
                retrying = true;
                control.recovering(true);
                if (consecutive == 0) firstExceeded = System.currentTimeMillis();
                overruns[consecutive++] = rtf;
                if (consecutive == CONSECUTIVE_OVERRUNS) {
                    String reason = String.format(Locale.ROOT,
                            "首次越界 %s · RTF %.2f / %.2f / %.2f · 预警值 %.2f",
                            Instant.ofEpochMilli(firstExceeded), overruns[0], overruns[1], overruns[2],
                            limit.rtf() + limit.overheadSeconds() / audioSeconds);
                    reloadForSegment(state, segment, control, reason);
                    consecutive = 0;
                }
                continue;
            }
            if (result.truncated()) {
                if (audioSeconds <= 2) throw new RecoveryError("片段仍触及模型输出长度上限");
                retrying = true;
                control.recovering(true);
                int middle = pcm.length / 4 * 2;
                byte[] first = Arrays.copyOfRange(pcm, 0, middle);
                byte[] last = Arrays.copyOfRange(pcm, middle, pcm.length);
                String left = Pcm.silent(first) ? "" : recognizeSegment(first, language, segment, control, state);
                var tail = new Segment(segment.entry(), segment.sample() + middle / 2);
                String right = Pcm.silent(last) ? "" : recognizeSegment(last, language, tail, control, state);
                return (left + " " + right).strip();
            }
            if (pair != null && !overLimit) watchdog.healthy(audioSeconds, rtf);
            return result.text();
        }
    }

    private double logRtf(long elapsedMillis, double audioSeconds) {
        double elapsedSeconds = elapsedMillis / 1000.0;
        log("INFO", String.format(Locale.ROOT, "片段 %d ms · RTF %.2f",
                elapsedMillis, elapsedSeconds / Math.max(.001, audioSeconds)));
        return elapsedSeconds;
    }

    private void reloadForSegment(SegmentRecovery state, Segment segment, Control control, String reason)
            throws Exception {
        String detail = tuning.device() + " · " + segment.entry() + " · " + Pcm.millis(segment.sample()) + " ms";
        RtfWatchdog.record(context, "WARN", run, modelId, detail + " · " + reason);
        if (state.reloaded) {
            RtfWatchdog.record(context, "ERROR", run, modelId, detail + " · 重载后片段仍未通过检查");
            throw new RecoveryError("模型重载后当前片段仍然异常");
        }
        state.reloaded = true;
        retrying = true;
        control.recovering(true);
        startRecovery(detail);
        awaitRecovery(control);
    }

    private synchronized void startRecovery(String detail) throws RecoveryError {
        if (closed) throw new RecoveryError("推理服务已停止");
        Session previous = session;
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                previous.process.destroyForcibly();
                if (!previous.process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IOException("无法结束旧推理进程");
                }
                awaitReady(startSession());
                RtfWatchdog.record(context, "INFO", run, modelId, detail + " · 模型重载完成");
                return null;
            } catch (Exception error) {
                Session failed = session;
                failed.process.destroyForcibly();
                RtfWatchdog.record(context, "ERROR", run, modelId, detail
                        + " · 模型重载失败（" + error.getClass().getSimpleName() + "）");
                throw error;
            }
        });
        recovery = task;
        new Thread(task, "Qwen ASR recovery").start();
    }

    private void awaitRecovery(Control control) throws Exception {
        FutureTask<Void> task = recovery;
        if (task == null) return;
        while (true) {
            control.run();
            try {
                task.get(250, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException waiting) {
                // Keep honouring task cancellation while the shared engine reloads.
            } catch (ExecutionException failed) {
                throw new RecoveryError("本地模型重载失败", failed.getCause());
            }
        }
    }

    private Recognition recognize(Session current, byte[] pcm, String language, Control control,
                                  boolean bulk) throws Exception {
        byte[] wav = Pcm.wav(pcm);
        String boundary = UUID.randomUUID().toString();
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        multipart.write(wav);
        if (language.matches("[a-z]{2,3}")) {
            multipart.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n"
                    + language).getBytes(StandardCharsets.US_ASCII));
        }
        multipart.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        boolean pace = pair != null && bulk;
        if (pace) performanceLimit.await(context, control);
        String text;
        String finish;
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            try {
                var response = request(current.url + "/v1/audio/transcriptions", engineKey,
                        "multipart/form-data; boundary=" + boundary, multipart.toByteArray(), 90_000);
                text = response.getString("text");
                finish = response.optString("finish_reason", "");
            } catch (HttpError error) {
                if (error.status != 404 && error.status != 405 && error.status != 501) throw error;
                JSONArray content = new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "Transcribe the audio."))
                        .put(new JSONObject().put("type", "input_audio").put("input_audio", new JSONObject()
                                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav")));
                var choice = chatChoice(current.url, engineKey, null,
                        new JSONArray().put(new JSONObject().put("role", "user").put("content", content)), 90_000);
                text = choice.getJSONObject("message").getString("content");
                finish = choice.optString("finish_reason", "");
            }
            int marker = text.indexOf("<asr_text>");
            if (marker >= 0) text = text.substring(marker + "<asr_text>".length());
            text = text.replace("<|im_end|>", "").replace("<|endoftext|>", "").trim();
            return new Recognition(text, switch (finish) {
                case "length", "max_tokens", "context_length" -> true;
                default -> false;
            }, android.os.SystemClock.elapsedRealtime() - started);
        } finally {
            if (pace) performanceLimit.complete();
        }
    }

    boolean hasTranslation() { return translator != null; }

    String source() { return config.source(); }

    String translateLive(Pcm.Cue cue, String previous) throws Exception {
        return translator.translate(new Translator.Request(cue.startMs(), cue.source(), previous,
                config.source(), config.target()), 15_000);
    }

    String translate(String text, String previous, String target, long segmentId) throws Exception {
        if (translator == null) throw new IOException("请启用翻译");
        return translator.translate(new Translator.Request(segmentId, text, previous, config.source(), target), 90_000);
    }

    static String chat(String base, String key, String model, JSONArray messages, int timeout) throws Exception {
        return chatChoice(base, key, model, messages, timeout).getJSONObject("message").getString("content");
    }

    private static JSONObject chatChoice(String base, String key, String model, JSONArray messages, int timeout)
            throws Exception {
        JSONObject payload = new JSONObject().put("messages", messages).put("temperature", 0)
                .put("max_tokens", 512).put("stream", false);
        if (model != null) payload.put("model", model);
        return request(base + "/v1/chat/completions", key, "application/json",
                payload.toString().getBytes(StandardCharsets.UTF_8), timeout)
                .getJSONArray("choices").getJSONObject(0);
    }

    private static JSONObject request(String url, String key, String type, byte[] body, int timeout)
            throws Exception {
        var request = new Request.Builder().url(url);
        if (!key.isBlank()) request.header("Authorization", "Bearer " + key);
        if (body != null) request.post(RequestBody.create(body, MediaType.get(type)));
        var call = HTTP.newBuilder().readTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout, TimeUnit.MILLISECONDS).build().newCall(request.build());
        var result = new CompletableFuture<JSONObject>();
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                result.completeExceptionally(error);
            }

            @Override public void onResponse(Call call, Response response) {
                try (response) {
                    if (!response.isSuccessful() || response.body() == null) throw new HttpError(response.code());
                    byte[] bytes = Wire.readBounded(response.body().byteStream(), 2 * 1024 * 1024);
                    result.complete(new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
                } catch (Exception error) {
                    result.completeExceptionally(error);
                }
            }
        });
        try {
            return result.get();
        } catch (InterruptedException cancelled) {
            call.cancel();
            throw cancelled;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    static String baseUrl(String value) {
        String clean = value.strip().replaceFirst("/+$", "").replaceFirst("/v1$", "");
        URI uri = URI.create(clean);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) throw new IllegalArgumentException("请填写有效的 HTTP(S) 服务地址");
        return clean;
    }

    static String newPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (recovery != null) recovery.cancel(true);
        if (session.process != null) session.process.destroyForcibly();
        if (translator != null) translator.close();
    }

    static final class RecoveryError extends IOException {
        RecoveryError(String message) { super(message); }
        RecoveryError(String message, Throwable cause) { super(message, cause); }
    }

    static final class HttpError extends IOException {
        final int status;
        HttpError(int status) {
            super("推理服务返回 HTTP " + status);
            this.status = status;
        }
    }
}
