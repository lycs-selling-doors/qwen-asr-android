package dev.workspace.qwen.asr;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
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

import static dev.workspace.qwen.asr.Wire.ApiError;
import static dev.workspace.qwen.asr.Wire.Response;

final class Jobs implements AutoCloseable {
    private static final long MAX_AUDIO_BYTES = 1024L * 1024 * 1024;
    private Inference engine;
    private final File cacheDir;
    private final File resultsDir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, JSONObject> cache = new LinkedHashMap<>();
    private boolean stopped;

    Jobs(Context context) throws IOException {
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

    synchronized Response route(String method, String target, JSONObject payload)
            throws IOException, JSONException {
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
            if (!engine.hasTranslation()) {
                throw new ApiError(409, "Kikoeta 请求中文字幕，请先配置翻译地址和模型");
            }
            jobs.values().removeIf(job -> job.terminal() && jobs.size() >= 64);
            if (jobs.size() >= 64) throw new ApiError(409, "本地队列已满");
            Job job = new Job(payload);
            jobs.put(job.id, job);
            worker.execute(() -> process(job));
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
            return Response.json(200, job.eventsAfter(after));
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
                        worker.execute(() -> process(job));
                    }
                }
                default -> throw new ApiError(404, "找不到接口");
            }
            return Response.json(200, job.snapshot());
        }
        throw new ApiError(404, "找不到接口");
    }

    private void process(Job job) {
        synchronized (this) {
            if (stopped || !job.status.equals("queued")) return;
            job.executing = true;
            job.status = "running";
        }
        Exception failure = null;
        try {
            while (job.track < job.sources.size()) {
                checkpoint(job);
                Source source = job.sources.get(job.track);
                if (job.audio == null) {
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
                Pcm.decode(job.audio, 8, () -> checkpoint(job), (start, pcm) -> {
                    if (start < job.nextSample) return;
                    checkpoint(job);
                    if (!Pcm.silent(pcm)) {
                        String text = engine.transcribe(pcm);
                        checkpoint(job);
                        if (!text.isBlank()) {
                            String translation = engine.translate(text, "简体中文");
                            if (translation.isBlank()) throw new IOException("翻译服务返回了空文本");
                            job.cues.add(new Pcm.Cue(Pcm.millis(start), Pcm.millis(start + pcm.length / 2),
                                    text, translation));
                        }
                    }
                    job.nextSample = start + pcm.length / 2;
                });
                checkpoint(job);
                String url = "/api/v1/output/" + job.id + "/" + job.track + ".lrc";
                String name = source.name.replaceFirst("\\.[^.]+$", "") + ".zh.lrc";
                File result = new File(resultsDir, job.id + "-" + job.track + ".lrc");
                Files.write(result.toPath(), Pcm.lrc(job.cues, true).getBytes(StandardCharsets.UTF_8));
                synchronized (this) {
                    job.results.put(new JSONObject().put("status", "done").put("file_index", job.track)
                            .put("outputs", new JSONArray().put(name)).put("download_urls", new JSONArray().put(url)));
                    if (job.track < job.trackPaths.length()) {
                        job.cachedFiles.put(new JSONObject().put("track_path", job.trackPaths.getString(job.track))
                                .put("name", name).put("download_url", url));
                    }
                    event(job, "file_done", "", "已生成 " + name);
                }
                job.track++;
                job.nextSample = 0;
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
                    job.error = failure instanceof Wire.ApiError
                            ? failure.getMessage() : "处理音频失败（" + failure.getClass().getSimpleName() + "）";
                    if (failure instanceof Inference.HttpError) job.error = failure.getMessage();
                    event(job, "file_error", "", job.error);
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
            }
        }
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
        } catch (JSONException ignored) {}
    }

    private void download(Job job, Source source, File file) throws Exception {
        URI uri = source.url;
        boolean allowHeaders = true;
        for (int redirects = 0; redirects <= 5; redirects++) {
            checkpoint(job);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                if (allowHeaders) source.headers.forEach(connection::setRequestProperty);
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("下载重定向缺少地址");
                    URI next = httpUrl(uri.resolve(location).toString());
                    allowHeaders &= sameOrigin(uri, next);
                    uri = next;
                    continue;
                }
                if (status / 100 != 2) throw new ApiError(409, "音频服务器返回 HTTP " + status);
                if (connection.getContentLengthLong() > MAX_AUDIO_BYTES) throw new ApiError(413, "音频超过 1 GiB");
                try (var input = connection.getInputStream(); var output = Files.newOutputStream(file.toPath())) {
                    long total = 0;
                    byte[] buffer = new byte[64 * 1024];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        checkpoint(job);
                        total += size;
                        if (total > MAX_AUDIO_BYTES) throw new ApiError(413, "音频超过 1 GiB");
                        output.write(buffer, 0, size);
                    }
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new ApiError(409, "音频下载重定向次数过多");
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equals(second.getScheme()) && first.getHost().equalsIgnoreCase(second.getHost())
                && port(first) == port(second);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
    }

    private static URI httpUrl(String value) {
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("无效的音频地址");
        }
        return uri;
    }

    @Override public synchronized void close() {
        stopped = true;
        worker.shutdownNow();
        jobs.values().stream().filter(job -> !job.executing).forEach(Job::clearTemp);
    }

    private static final class Suspended extends RuntimeException {}
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
        String status = "queued";
        String error = "";
        boolean executing;
        File audio;
        int track;
        long nextSample;

        Job(JSONObject payload) throws JSONException, ApiError {
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
                        // The media API uses these headers. Leave framing and routing to HttpURLConnection.
                        if (key.equalsIgnoreCase("Authorization") || key.equalsIgnoreCase("Cookie")
                                || key.equalsIgnoreCase("Referer") || key.equalsIgnoreCase("User-Agent")) {
                            String value = raw.getString(key);
                            if (value.contains("\r") || value.contains("\n")) throw new ApiError(400, "无效的音频请求头");
                            headers.put(key, value);
                        }
                    }
                }
                String name = source.getString("name").replaceAll("[\\r\\n]", " ").strip();
                sources.add(new Source(httpUrl(source.getString("url")), name.isEmpty() ? "track" : name, headers));
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
                    .put("closed", terminal() || status.equals("paused"));
        }

        boolean terminal() { return status.equals("completed") || status.equals("failed") || status.equals("cancelled"); }

        void clearTemp() {
            if (audio != null) {
                audio.delete();
                audio = null;
            }
        }
    }
}
