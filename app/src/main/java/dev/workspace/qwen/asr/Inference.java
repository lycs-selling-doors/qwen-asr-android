package dev.workspace.qwen.asr;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.UUID;

final class Inference implements AutoCloseable {
    record Config(String asrUrl, String translationUrl, String translationModel, String apiKey,
                  String target, String password) {}

    static final String MODEL = "model.gguf";
    static final String PROJECTOR = "mmproj.gguf";
    private static final String BUNDLED_URL = "http://127.0.0.1:2381";
    private final Config config;
    private final String asrUrl;
    private final String engineKey;
    private final Process process;

    Inference(Context context, Config config) throws IOException {
        this.config = config;
        if (!config.asrUrl().isBlank()) {
            asrUrl = baseUrl(config.asrUrl());
            engineKey = "";
            process = null;
            return;
        }

        File libDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File executable = new File(libDir, "libqwen_llama_server.so");
        File model = new File(context.getFilesDir(), MODEL);
        File projector = new File(context.getFilesDir(), PROJECTOR);
        if (!executable.canExecute()) {
            throw new IOException("此 APK 未打包 llama-server，请填写已运行的引擎地址或构建 arm64 版 APK");
        }
        if (!model.isFile() || !projector.isFile()) {
            throw new IOException("请先导入 GGUF 和配套 mmproj");
        }

        asrUrl = BUNDLED_URL;
        engineKey = newPassword();
        File dspDir = new File(context.getFilesDir(), "htp");
        Files.createDirectories(dspDir.toPath());
        String[] libraries = context.getAssets().list("htp");
        if (libraries == null || libraries.length == 0) throw new IOException("APK 缺少 HTP 库");
        for (String library : libraries) {
            try (InputStream input = context.getAssets().open("htp/" + library)) {
                Files.copy(input, new File(dspDir, library).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        ProcessBuilder builder = new ProcessBuilder(executable.getAbsolutePath(),
                "--host", "127.0.0.1", "--port", "2381", "--api-key", engineKey,
                "-m", model.getAbsolutePath(), "--mmproj", projector.getAbsolutePath(),
                "--device", "HTP0", "-ngl", "99", "--no-mmproj-offload", "-c", "2048",
                "--parallel", "1", "-lv", "4");
        builder.directory(libDir);
        // The installed build links OpenCL, even when it only executes on HTP.
        // Resolve the system C++ runtime before looking up the device's vendor libraries.
        builder.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":/system/lib64:/vendor/lib64");
        builder.environment().put("ADSP_LIBRARY_PATH", dspDir.getAbsolutePath());
        process = builder.redirectErrorStream(true).start();
        new Thread(() -> {
            try (var logs = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean startup = true;
                while ((line = logs.readLine()) != null) {
                    if (startup || line.contains("eval time") || line.contains("total time")) {
                        Log.i("QwenLlama", line.replace(engineKey, "***"));
                    }
                    if (line.contains("listening on")) startup = false;
                }
            } catch (IOException ignored) {}
        }, "llama-server logs").start();
    }

    void awaitReady() throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + (process == null ? 10_000 : 180_000);
        do {
            if (process != null && !process.isAlive()) {
                throw new IOException("llama-server 提前退出，退出码 " + process.exitValue() + "。请查看 QwenLlama 日志。");
            }
            try {
                request(asrUrl + "/health", engineKey, null, null, 3000);
                return;
            } catch (IOException error) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw error;
                Thread.sleep(750);
            }
        } while (!Thread.currentThread().isInterrupted());
        throw new InterruptedException();
    }

    synchronized String transcribe(byte[] pcm) throws Exception {
        byte[] wav = Pcm.wav(pcm);
        String boundary = UUID.randomUUID().toString();
        ByteArrayOutputStream multipart = new ByteArrayOutputStream();
        multipart.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        multipart.write(wav);
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
        int marker = text.indexOf("<asr_text>");
        if (marker >= 0) text = text.substring(marker + "<asr_text>".length());
        return text.replace("<|im_end|>", "").replace("<|endoftext|>", "").trim();
    }

    boolean hasTranslation() {
        return !config.translationUrl().isBlank() && !config.translationModel().isBlank();
    }

    String translateLive(String text) throws Exception {
        return translate(text, config.target(), 15_000);
    }

    String translate(String text, String target) throws Exception {
        return translate(text, target, 90_000);
    }

    private String translate(String text, String target, int timeout) throws Exception {
        String instructions = "Translate the user's transcript into " + target
                + ". Preserve meaning and tone. If it is already in that language, retain its wording."
                + " Return only the translated transcript.";
        return chat(baseUrl(config.translationUrl()), config.apiKey(), config.translationModel(),
                new JSONArray().put(new JSONObject().put("role", "system").put("content", instructions))
                        .put(new JSONObject().put("role", "user").put("content", text)), timeout).trim();
    }

    private static String chat(String base, String key, String model, JSONArray messages, int timeout) throws Exception {
        JSONObject payload = new JSONObject().put("messages", messages).put("temperature", 0)
                .put("max_tokens", 512).put("stream", false);
        if (model != null) payload.put("model", model);
        return request(base + "/v1/chat/completions", key, "application/json",
                payload.toString().getBytes(StandardCharsets.UTF_8), timeout)
                .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    private static JSONObject request(String url, String key, String type, byte[] body, int timeout)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            if (!key.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + key);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", type);
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status / 100 != 2) throw new HttpError(status);
            try (var input = connection.getInputStream()) {
                byte[] bytes = Wire.readBounded(input, 2 * 1024 * 1024);
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
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
    }

    static final class HttpError extends IOException {
        final int status;
        HttpError(int status) {
            super("推理服务返回 HTTP " + status);
            this.status = status;
        }
    }
}
