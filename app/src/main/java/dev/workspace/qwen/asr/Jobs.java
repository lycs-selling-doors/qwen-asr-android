package dev.workspace.qwen.asr;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.workspace.qwen.asr.Wire.ApiError;
import static dev.workspace.qwen.asr.Wire.Response;

final class Jobs implements AutoCloseable {
    private static final long MAX_AUDIO_BYTES = 1024L * 1024 * 1024;
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 20 * 60;
    private static final int SEGMENT_SECONDS = 8;
    private static final long EVENT_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(1500);
    private static final int MAX_EVENT_WAITERS = 2;
    private Inference engine;
    private final Context context;
    private final File cacheDir;
    private final File resultsDir;
    private final ExecutorService worker;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, JSONObject> cache = new LinkedHashMap<>();
    private boolean stopped;
    private int eventWaiters;

    Jobs(Context context) throws IOException {
        this(context, Executors.newSingleThreadExecutor());
    }

    Jobs(Context context, ExecutorService worker) throws IOException {
        this.context = context;
        this.worker = worker;
        cacheDir = context.getCacheDir();
        resultsDir = new File(context.getFilesDir(), "results");
        Files.createDirectories(resultsDir.toPath());
        File[] entries = resultsDir.listFiles((dir, file) -> file.endsWith(".json"));
        if (entries == null) return;
        for (File entry : entries) {
            try {
                JSONObject data = new JSONObject(new String(Files.readAllBytes(entry.toPath()), StandardCharsets.UTF_8));
                cache.put(data.getString("work_id"), data);
            } catch (JSONException | IOException ignored) {}
        }
    }

    synchronized void setEngine(Inference engine) {
        this.engine = engine;
    }

    synchronized Inference engine() { return engine; }

    synchronized boolean detachIfIdle() {
        if (pending() != 0) return false;
        engine = null;
        return true;
    }

    synchronized long pending() {
        return jobs.values().stream().filter(job -> !job.terminal() && !job.status.equals("paused")).count();
    }

    synchronized Response route(String method, String target, JSONObject payload)
            throws IOException, JSONException, InterruptedException {
        if (stopped) throw new ApiError(503, "服务已停止");
        String[] request = target.split("\\?", 2);
        String path = request[0];
        if ("GET".equals(method)) {
            if (path.equals("/api/v1/health")) {
                return Response.json(200, new JSONObject().put("ok", true).put("ready", engine != null));
            }
            if (path.equals("/api/v1/cache")) {
                return Response.json(200, new JSONObject().put("entries", new JSONArray(cache.values())));
            }
            if (path.matches("/api/v1/output/[a-f0-9-]{36}/[0-9]{1,3}\\.lrc")) {
                String name = path.substring("/api/v1/output/".length()).replace('/', '-');
                File result = new File(resultsDir, name);
                if (!result.isFile()) throw new ApiError(404, "找不到字幕");
                return new Response(200, "text/plain; charset=utf-8", Files.readAllBytes(result.toPath()));
            }
        }
        if (path.equals("/api/v1/jobs") && "POST".equals(method)) {
            if (engine == null) throw new ApiError(503, "正在加载听写模型，请等 Qwen 听写显示服务已启动后重试");
            String outputMode = resolveOutputMode(payload);
            boolean needsTranslation = !"source".equals(outputMode);
            if (needsTranslation && !engine.hasTranslation()) {
                throw new ApiError(409, "当前 Kikoeta 输出包含译文，请先开启翻译或将输出改为「仅原文」");
            }
            jobs.values().removeIf(job -> job.terminal() && jobs.size() >= 64);
            if (jobs.size() >= 64) throw new ApiError(409, "本地队列已满");
            Job job = new Job(payload, outputMode);
            jobs.put(job.id, job);
            schedule(job);
            return Response.json(201, job.snapshot());
        }

        String[] parts = path.split("/");
        if (parts.length < 5 || parts.length > 6 || !path.startsWith("/api/v1/jobs/")) {
            throw new ApiError(404, "找不到接口");
        }
        Job job = jobs.get(parts[4]);
        if (job == null) throw new ApiError(404, "找不到任务");
        if (parts.length == 5 && "GET".equals(method)) return Response.json(200, job.snapshot());
        if (parts.length == 6 && "GET".equals(method) && parts[5].equals("events")) {
            int after = 0;
            if (request.length == 2 && request[1].matches("after=[0-9]{1,9}")) {
                after = Integer.parseInt(request[1].substring(6));
            }
            return pollEvents(job, after);
        }
        if (parts.length == 6 && "POST".equals(method)) {
            switch (parts[5]) {
                case "cancel" -> {
                    if (!job.terminal()) {
                        job.status = job.executing ? "cancelling" : "cancelled";
                        if (!job.executing) job.clearTemp();
                    }
                }
                case "preempt" -> {
                    if (job.status.equals("queued") || job.status.equals("running")) {
                        job.status = job.executing ? "pausing" : "paused";
                    }
                }
                case "resume" -> {
                    if (job.status.equals("paused")) {
                        job.status = "queued";
                        schedule(job);
                    }
                }
                default -> throw new ApiError(404, "找不到接口");
            }
            notifyAll();
            return Response.json(200, job.snapshot());
        }
        throw new ApiError(404, "找不到接口");
    }

    private Response pollEvents(Job job, int after) throws InterruptedException, JSONException, ApiError {
        if (after == job.events.length() && !job.eventsClosed() && !job.eventWaiter && eventWaiters < MAX_EVENT_WAITERS) {
            long deadline = System.nanoTime() + EVENT_WAIT_NANOS;
            job.eventWaiter = true;
            eventWaiters++;
            try {
                while (!stopped && after == job.events.length() && !job.eventsClosed()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                }
            } finally {
                job.eventWaiter = false;
                eventWaiters--;
            }
        }
        if (stopped) throw new ApiError(503, "服务已停止");
        return Response.json(200, job.eventsAfter(after));
    }

    private void schedule(Job job) {
        if (job.scheduled) return;
        job.scheduled = true;
        worker.execute(() -> process(job));
    }

    private void process(Job job) {
        Inference current;
        synchronized (this) {
            job.scheduled = false;
            if (stopped || !job.status.equals("queued")) return;
            current = engine;
            job.executing = true;
            job.status = "running";
        }
        Exception failure = null;
        try {
            if (current == null) throw new ApiError(503, "模型尚未就绪，请重新提交任务");
            while (job.track < job.sources.size()) {
                checkpoint(job);
                Source source = job.sources.get(job.track);
                if (job.audio == null) {
                    job.stage = Stage.DOWNLOAD;
                    event(job, "status", "running", "获取音频");
                    File audio = File.createTempFile("kikoeta-", ".audio", cacheDir);
                    try {
                        download(job, source, audio);
                        job.audio = audio;
                    } finally {
                        if (job.audio == null) audio.delete();
                    }
                }
                event(job, "status", "asr", "转写 " + source.name);
                var prefs = DictationService.prefs(context);
                String decoder = prefs.getString("audioDecoder", "auto");
                String targetLanguage = prefs.getString("target", "简体中文");
                boolean needsTranslation = !"source".equals(job.outputMode);
                job.stage = Stage.DECODE;
                Pcm.decode(job.audio, SEGMENT_SECONDS, decoder, () -> checkpoint(job), (start, pcm) -> {
                    if (start < job.nextSample) return;
                    checkpoint(job);
                    if (!Pcm.silent(pcm)) {
                        job.stage = Stage.RECOGNIZE;
                        String text = current.transcribe(pcm);
                        checkpoint(job);
                        if (!text.isBlank()) {
                            long startMs = Pcm.millis(start);
                            String translation = "";
                            if (needsTranslation) {
                                job.stage = Stage.TRANSLATE;
                                String previous = nearbySource(job.cues, startMs);
                                try {
                                    translation = current.translate(text, previous, targetLanguage, start);
                                } catch (Translator.LanguageUndetermined ignored) {
                                    translation = "";
                                }
                                if (translation.isBlank()) {
                                    job.untranslated++;
                                    translation = text;
                                }
                            }
                            job.cues.add(new Pcm.Cue(startMs, Pcm.millis(start + pcm.length / 2), text, translation));
                        }
                    }
                    job.nextSample = start + pcm.length / 2;
                    job.stage = Stage.DECODE;
                });
                checkpoint(job);
                if (job.nextSample == 0) throw new ApiError(400, "未读到有效音频样本");
                job.stage = Stage.SAVE;
                String url = "/api/v1/output/" + job.id + "/" + job.track + ".lrc";
                String suffix = switch (job.outputMode) {
                    case "translation" -> ".zh.lrc";
                    case "bilingual" -> ".bi.lrc";
                    default -> ".lrc";
                };
                String name = source.name.replaceFirst("\\.[^.]+$", "") + suffix;
                File result = new File(resultsDir, job.id + "-" + job.track + ".lrc");
                Files.write(result.toPath(), Pcm.lrc(job.cues, job.outputMode).getBytes(StandardCharsets.UTF_8));
                synchronized (this) {
                    job.results.put(new JSONObject().put("status", "done").put("file_index", job.track)
                            .put("outputs", new JSONArray().put(name)).put("download_urls", new JSONArray().put(url)));
                    if (job.track < job.trackPaths.length()) {
                        job.cachedFiles.put(new JSONObject().put("track_path", job.trackPaths.getString(job.track))
                                .put("name", name).put("download_url", url));
                    }
                    String untranslated = job.untranslated == 0 ? "" : "，" + job.untranslated + " 段保留原文";
                    event(job, "file_done", "", "已生成 " + name + untranslated);
                }
                job.track++;
                job.nextSample = 0;
                job.untranslated = 0;
                job.cues.clear();
                job.clearTemp();
            }
        } catch (Suspended ignored) {
        } catch (Exception error) {
            failure = error;
        } finally {
            synchronized (this) {
                job.executing = false;
                if (job.status.equals("cancelling") || stopped) {
                    job.status = "cancelled";
                } else if (job.status.equals("pausing")) {
                    job.status = "paused";
                } else if (failure != null) {
                    job.status = "failed";
                    job.error = (job.track + 1) + " 号音轨 · " + job.stage.label + "失败 · " + failureDetail(failure, job.stage);
                    event(job, "file_error", "", job.error);
                    RuntimeLog.add("ERROR", "Kikoeta", job.id, current == null ? "" : current.modelId(), job.error);
                } else {
                    try {
                        saveCache(job);
                        job.status = "completed";
                    } catch (IOException | JSONException error) {
                        job.status = "failed";
                        job.error = "保存字幕缓存失败";
                    }
                }
                if (!job.status.equals("paused")) job.clearTemp();
                notifyAll();
            }
        }
    }

    private static String nearbySource(List<Pcm.Cue> cues, long startMs) {
        if (cues.isEmpty()) return "";
        Pcm.Cue previous = cues.get(cues.size() - 1);
        if (startMs - previous.endMs() > SEGMENT_SECONDS * 1000L) return "";
        return previous.source().substring(0, Math.min(previous.source().length(), 256));
    }

    private static String failureDetail(Exception error, Stage stage) {
        if (error instanceof ApiError || error instanceof Inference.HttpError || error instanceof Translator.LocalError) {
            return error.getMessage();
        }
        if (error instanceof java.io.InterruptedIOException || error instanceof java.util.concurrent.TimeoutException) {
            return "响应超时";
        }
        return switch (stage) {
            case PREPARE -> "请检查模型与离线翻译配置";
            case DOWNLOAD -> "请检查音频来源、连接或剩余存储";
            case DECODE -> "请检查音频格式、文件完整性和解码器设置";
            case RECOGNIZE -> "请检查推理服务和模型状态";
            case TRANSLATE -> "请检查所选翻译方式及可用语言包";
            case SAVE -> "请检查设备剩余存储";
        };
    }

    private void saveCache(Job job) throws IOException, JSONException {
        if (job.workId.isBlank() || job.cachedFiles.length() == 0) return;
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        JSONObject previous = cache.get(job.workId);
        if (previous != null) {
            JSONArray files = previous.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                merged.put(file.getString("track_path"), file);
            }
        }
        for (int i = 0; i < job.cachedFiles.length(); i++) {
            JSONObject file = job.cachedFiles.getJSONObject(i);
            merged.put(file.getString("track_path"), file);
        }
        JSONObject entry = new JSONObject().put("job_id", job.id).put("work_id", job.workId)
                .put("files", new JSONArray(merged.values()));
        String key = UUID.nameUUIDFromBytes(job.workId.getBytes(StandardCharsets.UTF_8)).toString();
        File temp = new File(resultsDir, key + ".tmp");
        Files.write(temp.toPath(), entry.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), new File(resultsDir, key + ".json").toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        cache.put(job.workId, entry);
    }

    private synchronized void checkpoint(Job job) {
        if (stopped || !job.status.equals("running")) throw new Suspended();
    }

    private synchronized void event(Job job, String type, String stage, String message) {
        try {
            job.events.put(new JSONObject().put("type", type).put("stage", stage).put("message", message));
            notifyAll();
        } catch (JSONException ignored) {}
    }

    private String resolveOutputMode(JSONObject payload) {
        JSONObject flags = payload.optJSONObject("flags");
        if (flags != null && flags.has("enable_translate") && !flags.optBoolean("enable_translate", true)) {
            return "source";
        }
        String configured = DictationService.prefs(context).getString("kikoetaOutput", "source");
        return switch (configured) {
            case "translation", "bilingual", "source" -> configured;
            default -> "source";
        };
    }

    private void download(Job job, Source source, File file) throws IOException {
        var prefs = DictationService.prefs(context);
        boolean allowPrivate = prefs.getBoolean("allowPrivateAudio", true);
        List<URI> trusted = AudioFetch.parseOrigins(prefs.getString("kikoetaLanSource", ""));
        AudioFetch.download(source.url, source.headers, trusted, allowPrivate, file, MAX_AUDIO_BYTES,
                DOWNLOAD_TIMEOUT_SECONDS, () -> checkpoint(job));
    }

    @Override public synchronized void close() {
        stopped = true;
        notifyAll();
        worker.shutdownNow();
        jobs.values().stream().filter(job -> !job.executing).forEach(Job::clearTemp);
    }

    private static final class Suspended extends RuntimeException {}
    private enum Stage {
        PREPARE("准备任务"), DOWNLOAD("下载音频"), DECODE("解码音频"),
        RECOGNIZE("语音识别"), TRANSLATE("翻译"), SAVE("保存字幕");

        final String label;
        Stage(String label) { this.label = label; }
    }

    private record Source(URI url, String name, Map<String, String> headers) {}

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final List<Source> sources = new ArrayList<>();
        final List<Pcm.Cue> cues = new ArrayList<>();
        final JSONArray results = new JSONArray();
        final JSONArray events = new JSONArray();
        final JSONArray cachedFiles = new JSONArray();
        final JSONArray trackPaths;
        final String workId;
        final String outputMode;
        String status = "queued";
        String error = "";
        boolean executing;
        boolean scheduled;
        boolean eventWaiter;
        File audio;
        int track;
        long nextSample;
        int untranslated;
        Stage stage = Stage.PREPARE;

        Job(JSONObject payload, String outputMode) throws JSONException, ApiError {
            this.outputMode = outputMode;
            JSONArray files = payload.getJSONArray("files");
            if (files.length() < 1 || files.length() > 128) throw new ApiError(400, "任务需包含 1 至 128 条音轨");
            for (int i = 0; i < files.length(); i++) {
                JSONObject source = files.getJSONObject(i);
                Map<String, String> headers = new HashMap<>();
                JSONObject raw = source.optJSONObject("headers");
                if (raw != null) {
                    var keys = raw.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        // Leave framing and routing to the HTTP client.
                        if (key.equalsIgnoreCase("Authorization") || key.equalsIgnoreCase("Cookie")
                                || key.equalsIgnoreCase("Referer") || key.equalsIgnoreCase("User-Agent")
                                || key.equalsIgnoreCase("Origin") || key.equalsIgnoreCase("Accept")) {
                            String value = raw.getString(key);
                            if (value.contains("\r") || value.contains("\n")) throw new ApiError(400, "无效的音频请求头");
                            headers.put(key, value);
                        }
                    }
                }
                String name = source.getString("name").replaceAll("[\\r\\n]", " ").strip();
                sources.add(new Source(AudioFetch.url(source.getString("url")), name.isEmpty() ? "track" : name, headers));
            }
            JSONObject cache = payload.optJSONObject("cache");
            workId = cache == null ? "" : cache.optString("work_id").trim();
            JSONArray paths = cache == null ? null : cache.optJSONArray("track_paths");
            trackPaths = paths == null ? new JSONArray() : paths;
        }

        JSONObject snapshot() throws JSONException {
            return new JSONObject().put("job_id", id).put("status", status).put("error", error)
                    .put("results", results);
        }

        JSONObject eventsAfter(int after) throws JSONException {
            JSONArray delta = new JSONArray();
            for (int i = after; i < events.length(); i++) delta.put(events.get(i));
            return new JSONObject().put("events", delta).put("cursor", events.length())
                    .put("closed", eventsClosed());
        }

        boolean eventsClosed() { return terminal() || status.equals("paused"); }

        boolean terminal() { return status.equals("completed") || status.equals("failed") || status.equals("cancelled"); }

        void clearTemp() {
            if (audio != null) {
                audio.delete();
                audio = null;
            }
        }
    }
}
