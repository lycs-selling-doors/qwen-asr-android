package dev.workspace.qwen.asr;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
    private final Config config;
    private final String asrUrl;
    private final String engineKey;
    private final Process process;
    private final Translator translator;
    private final String modelId;
    private final NativeRuntime.Tuning tuning;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final InferenceScheduler scheduler = new InferenceScheduler();
    private volatile int layers;
    private volatile int totalLayers;
    private volatile String allocated = "";
    private static final java.util.regex.Pattern LAYERS = java.util.regex.Pattern.compile("offloaded (\\d{1,4})/(\\d{1,4}) layers");
    private static final java.util.regex.Pattern BUFFER = java.util.regex.Pattern.compile(
            "\\b(" + NativeRuntime.BACKEND + ")\\s+model buffer", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TIMING = java.util.regex.Pattern.compile(
            "(prompt eval time|eval time|total time)\\s*=\\s*(\\d{1,9}(?:\\.\\d{1,5})?)\\s*ms");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false).connectTimeout(5, TimeUnit.SECONDS).build();

    Inference(Context context, Config config) throws IOException {
        this.config = config;
        if (!config.asrUrl().isBlank()) {
            asrUrl = baseUrl(config.asrUrl());
            engineKey = "";
            process = null;
            modelId = "external";
            tuning = config.tuning();
        } else {
            Models.Pair pair = new Models(context).selected(config.modelId());
            if (pair == null) throw new IOException("请先安装主模型与匹配的 mmproj");
            modelId = pair.card().id();
            NativeRuntime.Tuning requested = config.tuning();
            if (NativeRuntime.cpuOnly()) {
                tuning = new NativeRuntime.Tuning("CPU", requested.threads(), requested.batch(),
                        requested.context(), requested.output(), 0);
            } else {
                NativeRuntime.Tuning htp = new NativeRuntime.Tuning("HTP0", requested.threads(), requested.batch(),
                        requested.context(), requested.output(), requested.layers());
                tuning = requested.device().equals("auto")
                        ? NativeRuntime.verified(context, modelId, htp) ? htp : new NativeRuntime.Tuning("CPU",
                            requested.threads(), requested.batch(), requested.context(), requested.output(), requested.layers())
                        : requested;
            }
            engineKey = newPassword();
            NativeRuntime.Started started = NativeRuntime.start(context, pair, tuning, engineKey);
            process = started.process();
            asrUrl = "http://127.0.0.1:" + started.port();
            new Thread(this::readLogs, "llama-server logs").start();
        }
        Translator next = null;
        try {
            next = switch (config.translationMode()) {
                case "offline" -> new Translator.Offline();
                case "online" -> new Translator.Online(config.translationUrl(), config.apiKey(), config.translationModel());
                default -> null;
            };
        } catch (RuntimeException error) {
            if (process != null) process.destroyForcibly();
            throw error;
        }
        translator = next;
        log("INFO", "加载引擎 " + (process == null ? "外部" : tuning.device()));
    }

    private void readLogs() {
        try (var logs = new java.io.BufferedReader(new java.io.InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = logs.readLine()) != null) {
                var offload = LAYERS.matcher(line);
                var buffer = BUFFER.matcher(line);
                var timing = TIMING.matcher(line);
                if (offload.find()) {
                    layers = Integer.parseInt(offload.group(1));
                    totalLayers = Integer.parseInt(offload.group(2));
                    log("INFO", "语言模型卸载 " + layers + "/" + totalLayers + " 层");
                } else if (buffer.find()) {
                    allocated = buffer.group(1);
                    log("INFO", "语言模型缓冲区 " + allocated);
                } else if (timing.find()) {
                    log("DEBUG", timing.group(1) + " " + timing.group(2) + " ms");
                } else if (line.contains("failed to load") || line.contains("error loading model")) {
                    log("ERROR", "llama.cpp 加载失败，请核对设备与配套模型");
                }
            }
        } catch (IOException ignored) {}
    }

    private void log(String level, String message) {
        RuntimeLog.add(level, "llama.cpp", run, modelId, message);
    }

    String modelId() { return modelId; }
    NativeRuntime.Tuning tuning() { return tuning; }
    int pending() { return scheduler.pending(); }
    boolean alive() { return process == null || process.isAlive(); }

    String backend() {
        if (process == null) return "外部引擎 · 设备未报告";
        if (NativeRuntime.cpuOnly()) return "ARM64 CPU";
        if (layers > 0 && !allocated.isBlank()) return "CPU 音频 · " + allocated + " " + layers + "/" + totalLayers
                + " 层" + (layers < totalLayers ? " · 混合执行" : "");
        return "CPU 音频 · CPU 文本";
    }

    void awaitReady() throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + (process == null ? 10_000 : 180_000);
        do {
            if (process != null && !process.isAlive()) {
                throw new IOException("llama-server 提前退出，退出码 " + process.exitValue() + "。请查看 QwenLlama 日志。");
            }
            try {
                request(asrUrl + "/health", engineKey, null, null, 3000);
                if (process != null && !NativeRuntime.cpuOnly() && !tuning.device().equals("CPU")
                        && (layers == 0 || !(allocated.equalsIgnoreCase(tuning.device())
                            || allocated.startsWith(tuning.device() + ":")))) {
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
        return scheduler.run(live, () -> recognize(pcm, language));
    }

    private String recognize(byte[] pcm, String language) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
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
        String text;
        try {
            text = request(asrUrl + "/v1/audio/transcriptions", engineKey,
                    "multipart/form-data; boundary=" + boundary, multipart.toByteArray(), 90_000)
                    .getString("text");
        } catch (HttpError error) {
            if (error.status != 404 && error.status != 405 && error.status != 501) throw error;
            JSONArray content = new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", "Transcribe the audio."))
                    .put(new JSONObject().put("type", "input_audio").put("input_audio", new JSONObject()
                            .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav")));
            text = chat(asrUrl, engineKey, null,
                    new JSONArray().put(new JSONObject().put("role", "user").put("content", content)), 90_000);
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - started;
        log("INFO", String.format(java.util.Locale.ROOT, "片段 %d ms · RTF %.2f", elapsed,
                elapsed / Math.max(1.0, Pcm.millis(pcm.length / 2))));
        int marker = text.indexOf("<asr_text>");
        if (marker >= 0) text = text.substring(marker + "<asr_text>".length());
        return text.replace("<|im_end|>", "").replace("<|endoftext|>", "").trim();
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
        JSONObject payload = new JSONObject().put("messages", messages).put("temperature", 0)
                .put("max_tokens", 512).put("stream", false);
        if (model != null) payload.put("model", model);
        return request(base + "/v1/chat/completions", key, "application/json",
                payload.toString().getBytes(StandardCharsets.UTF_8), timeout)
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
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

    @Override public void close() {
        if (process != null) process.destroyForcibly();
        if (translator != null) translator.close();
    }

    static final class HttpError extends IOException {
        final int status;
        HttpError(int status) {
            super("推理服务返回 HTTP " + status);
            this.status = status;
        }
    }
}
