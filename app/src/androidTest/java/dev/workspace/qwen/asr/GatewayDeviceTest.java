package dev.workspace.qwen.asr;

import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class GatewayDeviceTest {
    @Test public void gatewayContractsAndRebind() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var client = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                .readTimeout(120, TimeUnit.SECONDS).build();
        int port = port();
        var policy = AccessPolicy.parse(port, "", AccessPolicy.Mode.LOCAL, "", true, true, true, "qwen3-asr-flash");
        try (var jobs = new Jobs(context); var wire = new Wire(context, jobs, policy)) {
            String base = "http://127.0.0.1:" + port;
            String basic = Credentials.basic("admin", Secrets.get(context, "password", true));
            String openai = "Bearer " + Secrets.get(context, "openaiKey", true);
            String dash = "Bearer " + Secrets.get(context, "dashKey", true);

            assertEquals(401, get(client, base + "/v1/models", "Bearer invalid").code);
            assertEquals(200, get(client, base + "/v1/models", openai).code);
            assertFalse(new JSONObject(get(client, base + "/api/v1/health", basic).body).getBoolean("ready"));
            assertTrue(new JSONObject(get(client, base + "/api/v1/cache", basic).body).has("entries"));
            assertEquals(400, post(client, base + AudioApi.DASH_PATH, dash, json("{}")).code);
            assertEquals(400, post(client, base + "/v1/audio/transcriptions", openai, json("{}")).code);
            assertEquals(404, post(client, base + "/v1/audio/transcriptions", openai,
                    multipart("unknown-model", new byte[]{1})).code);

            try (var reserved = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                var blocked = AccessPolicy.parse(reserved.getLocalPort(), "", AccessPolicy.Mode.LOCAL,
                        "", true, true, true, "");
                assertThrows(java.io.IOException.class, () -> wire.rebind(blocked));
                assertEquals(200, get(client, base + "/v1/models", openai).code);
            }
            wire.rebind(AccessPolicy.parse(port, "", AccessPolicy.Mode.LOCAL, "", true, false, true, ""));
            assertEquals(404, get(client, base + "/v1/models", openai).code);
            assertEquals(200, get(client, base + "/api/v1/cache", basic).code);
        }
    }

    @Test public void kikoetaWaitsForEventsWhileOtherRequestsProceed() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var client = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build();
        int port = port();
        var policy = AccessPolicy.parse(port, "", AccessPolicy.Mode.LOCAL, "", true, false, false, "");
        var config = new Inference.Config("http://127.0.0.1:1", "http://127.0.0.1:1", "fixture", "", "en",
                "简体中文", "online", "", new NativeRuntime.Tuning("CPU", 1, 512, 2048, 256, 99));
        try (var engine = new Inference(context, config); var jobs = new Jobs(context, pausedWorker());
             var wire = new Wire(context, jobs, policy, new Wire.Authentication("fixture", "", ""))) {
            jobs.setEngine(engine);
            String base = "http://127.0.0.1:" + port;
            String auth = Credentials.basic("admin", "fixture");
            var created = post(client, base + "/api/v1/jobs", auth,
                    json("{\"files\":[{\"url\":\"https://example.invalid/speech.wav\",\"name\":\"fixture.wav\"}]}"));
            assertEquals(201, created.code);
            String job = base + "/api/v1/jobs/" + new JSONObject(created.body).getString("job_id");
            String events = job + "/events?after=0";

            long started = System.nanoTime();
            var empty = new JSONObject(get(client, events, auth).body);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 1000);
            assertFalse(empty.getBoolean("closed"));
            assertEquals(0, empty.getInt("cursor"));

            var reader = Executors.newSingleThreadExecutor();
            try {
                long before = wire.requests();
                var pending = reader.submit(() -> get(client, events, auth));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (wire.requests() == before && System.nanoTime() < deadline) Thread.sleep(10);
                assertTrue(wire.requests() > before);
                assertEquals(200, get(client, base + "/api/v1/health", auth).code);
                assertEquals(200, post(client, job + "/cancel", auth, json("{}")).code);
                assertTrue(new JSONObject(pending.get(3, TimeUnit.SECONDS).body).getBoolean("closed"));
                assertEquals("cancelled", new JSONObject(get(client, job, auth).body).getString("status"));
            } finally {
                reader.shutdownNow();
            }
        }
    }

    @Test public void kikoetaAcceptsSlowBodyAndClosesIdleConnection() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int port = port();
        var policy = AccessPolicy.parse(port, "", AccessPolicy.Mode.LOCAL, "", true, false, false, "");
        try (var jobs = new Jobs(context);
             var wire = new Wire(context, jobs, policy, new Wire.Authentication("fixture", "", ""));
             var idle = new Socket("127.0.0.1", port); var post = new Socket("127.0.0.1", port)) {
            idle.setSoTimeout(13_000);
            post.setSoTimeout(13_000);
            var output = post.getOutputStream();
            output.write(("POST /api/v1/jobs HTTP/1.1\r\nHost: localhost\r\nAuthorization: "
                    + Credentials.basic("admin", "fixture") + "\r\nContent-Length: 2\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
            Thread.sleep(4500);
            output.write("{}".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            String response = new String(Wire.readBounded(post.getInputStream(), 8192), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 503 "));
            assertEquals(-1, idle.getInputStream().read());
        }
    }

    private static java.util.concurrent.ExecutorService pausedWorker() {
        var worker = Executors.newSingleThreadExecutor();
        worker.submit(() -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        return worker;
    }

    @Test public void onDeviceShortAudio() throws Exception {
        String backend = InstrumentationRegistry.getArguments().getString("backend");
        org.junit.Assume.assumeTrue(backend != null && List.of("CPU", "HTP0").contains(backend));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var models = new Models(context);
        models.migrateLegacy();
        String modelId = InstrumentationRegistry.getArguments().getString("model", Models.LARGE.id());
        assertNotNull(models.selected(modelId));
        var config = new Inference.Config("", "", "", "", "en", "简体中文", "off", modelId,
                new NativeRuntime.Tuning(backend, 6, 512, 2048, 256, 99));
        try (var inference = new Inference(context, config); var audio = context.getAssets().open("probe-en.pcm")) {
            inference.awaitReady();
            byte[] pcm = Wire.readBounded(audio, 320_000);
            String result = inference.transcribe(pcm, "en", false);
            assertTrue(result.toLowerCase(java.util.Locale.ROOT).contains("speech"));
            assertTrue(inference.backend().contains(backend));
            System.out.println("Verified backend: " + inference.backend());
            var client = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                    .readTimeout(120, TimeUnit.SECONDS).build();
            int port = port();
            var policy = AccessPolicy.parse(port, "", AccessPolicy.Mode.LOCAL, "", true, true, true, "qwen3-asr-flash");
            try (var jobs = new Jobs(context); var wire = new Wire(context, jobs, policy)) {
                jobs.setEngine(inference);
                var base = "http://127.0.0.1:" + port;
                var openai = "Bearer " + Secrets.get(context, "openaiKey", true);
                byte[] wav = Pcm.wav(pcm);
                Reply response = post(client, base + "/v1/audio/transcriptions", openai, multipart(inference.modelId(), wav));
                assertEquals(200, response.code);
                assertTrue(new JSONObject(response.body).getString("text").toLowerCase(java.util.Locale.ROOT).contains("speech"));

                var dash = "Bearer " + Secrets.get(context, "dashKey", true);
                var request = new JSONObject().put("model", "qwen3-asr-flash")
                        .put("input", new JSONObject().put("messages", new org.json.JSONArray()
                                .put(new JSONObject().put("role", "user").put("content", new org.json.JSONArray()
                                        .put(new JSONObject().put("audio", "data:audio/wav;base64,"
                                                + java.util.Base64.getEncoder().encodeToString(wav)))))))
                        .put("parameters", new JSONObject().put("asr_options", new JSONObject().put("language", "en")));
                var reply = post(client, base + AudioApi.DASH_PATH, dash, json(request.toString()));
                assertEquals(200, reply.code);
                assertTrue(new JSONObject(reply.body).getJSONObject("output").getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getJSONArray("content").getJSONObject(0)
                        .getString("text").toLowerCase(java.util.Locale.ROOT).contains("speech"));
            }
        }
    }

    @Test public void downloadSmallModelOnWifi() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("downloadSmall")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var models = new Models(context);
        var last = new java.util.concurrent.atomic.AtomicLong(-1);
        models.install(Models.SMALL, null, null, false, progress -> {
            long portion = progress.bytes() * 10 / progress.total();
            if (last.getAndSet(portion) == portion) return;
            var status = new android.os.Bundle();
            status.putString("stream", "Downloaded " + progress.bytes() + "/" + progress.total() + " bytes\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        });
        assertNotNull(models.installed(Models.SMALL));
    }

    @Test public void recognizeSpeechNearEndOfLongWav() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("longAudio")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var config = new Inference.Config("", "", "", "", "en", "简体中文", "off", Models.SMALL.id(),
                new NativeRuntime.Tuning("CPU", 6, 512, 2048, 256, 99));
        File audio = File.createTempFile("long-wav-", ".wav", context.getCacheDir());
        try (var probe = context.getAssets().open("probe-en.pcm"); var engine = new Inference(context, config)) {
            var speech = Wire.readBounded(probe, 320_000);
            int durationSeconds = 6 * 60 + 12;
            int speechOffsetSeconds = 6 * 60 + 1;
            int dataBytes = durationSeconds * Pcm.RATE * 2;
            var header = java.nio.ByteBuffer.wrap(Pcm.wav(new byte[0])).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            header.putInt(4, 36 + dataBytes).putInt(40, dataBytes);
            try (var wav = new java.io.RandomAccessFile(audio, "rw")) {
                wav.write(header.array());
                wav.setLength(44L + dataBytes);
                wav.seek(44L + speechOffsetSeconds * Pcm.RATE * 2L);
                wav.write(speech);
            }
            engine.awaitReady();
            var recognized = new java.util.ArrayList<Pcm.Cue>();
            long[] decodedSamples = {0};
            Pcm.decode(audio, 8, "auto", () -> {}, (start, pcm) -> {
                assertEquals(decodedSamples[0], start);
                decodedSamples[0] = start + pcm.length / 2;
                if (!Pcm.silent(pcm)) recognized.add(new Pcm.Cue(Pcm.millis(start), Pcm.millis(decodedSamples[0]),
                        engine.transcribe(pcm), ""));
            });
            assertEquals((long) durationSeconds * Pcm.RATE, decodedSamples[0]);
            assertEquals(1, recognized.size());
            assertEquals(360_000L, recognized.get(0).startMs());
            assertTrue(recognized.get(0).source().toLowerCase(java.util.Locale.ROOT).contains("speech"));
            try (var translator = new Translator.Offline()) {
                assertThrows(Translator.LanguageUndetermined.class,
                        () -> translator.translate(new Translator.Request(0, "…", "", "auto", "en"), 15000));
                assertEquals("…", translator.translate(new Translator.Request(0, "…",
                        "Today we are testing speech recognition in English.", "auto", "en"), 15000));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(audio.toPath());
        }
    }

    @Test public void importSmallModel() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("importSmall")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var models = new Models(context);
        var main = new java.io.File(context.getCacheDir(), "handoff-light-main.gguf");
        var projector = new java.io.File(context.getCacheDir(), "handoff-light-mmproj.gguf");
        try {
            models.install(Models.SMALL, android.net.Uri.fromFile(main), android.net.Uri.fromFile(projector),
                    false, progress -> {});
            assertNotNull(models.installed(Models.SMALL));
        } finally {
            java.nio.file.Files.deleteIfExists(main.toPath());
            java.nio.file.Files.deleteIfExists(projector.toPath());
        }
    }

    @Test public void serveSdkFixtures() throws Exception {
        String portValue = InstrumentationRegistry.getArguments().getString("sdkPort");
        org.junit.Assume.assumeNotNull(portValue);
        int port = Integer.parseInt(portValue);
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var config = new Inference.Config("", "", "", "", "en", "简体中文", "off", Models.LARGE.id(),
                new NativeRuntime.Tuning("CPU", 6, 512, 2048, 256, 99));
        var policy = AccessPolicy.parse(port, "", AccessPolicy.Mode.LOCAL, "", true, true, true, "qwen3-asr-flash");
        try (var engine = new Inference(context, config); var jobs = new Jobs(context)) {
            engine.awaitReady();
            jobs.setEngine(engine);
            try (var wire = new Wire(context, jobs, policy,
                    new Wire.Authentication("fixture-basic", "fixture-openai", "fixture-dash"))) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
                while (System.nanoTime() < deadline && (wire.requests() < 4 || wire.busy())) Thread.sleep(100);
                assertTrue("SDK smoke client did not complete requests", wire.requests() >= 4 && !wire.busy());
            }
        }
    }

    @Test public void translateWithInstalledPackages() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("offline")));
        var manager = com.google.mlkit.common.model.RemoteModelManager.getInstance();
        var conditions = new com.google.mlkit.common.model.DownloadConditions.Builder().requireWifi().build();
        for (String language : List.of("ja", "zh")) {
            if (Translator.Offline.installed().contains(language)) continue;
            var model = new com.google.mlkit.nl.translate.TranslateRemoteModel.Builder(language).build();
            com.google.android.gms.tasks.Tasks.await(manager.download(model, conditions), 40, TimeUnit.SECONDS);
        }
        try (var translator = new Translator.Offline()) {
            String english = "Hello. The weather is nice today.";
            String japanese = "こんにちは。今日はいい天気ですね。";
            String first = translator.translate(new Translator.Request(0, english, "", "en", "zh"), 15000);
            String second = translator.translate(new Translator.Request(3000, japanese, "", "ja", "zh"), 15000);
            String subtitle = Pcm.srt(List.of(new Pcm.Cue(0, 3000, english, first),
                    new Pcm.Cue(3000, 6000, japanese, second)));
            assertTrue(first.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN));
            assertTrue(second.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN));
            assertTrue(subtitle.contains("00:00:03,000 --> 00:00:06,000") && subtitle.contains(second));
        }
    }

    private static int port() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }

    private static RequestBody json(String value) { return RequestBody.create(value, MediaType.get("application/json")); }

    private static RequestBody multipart(String model, byte[] bytes) {
        return new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("language", "en")
                .addFormDataPart("file", "probe.wav", RequestBody.create(bytes, MediaType.get("audio/wav"))).build();
    }

    private record Reply(int code, String body) {}

    private static Reply get(OkHttpClient client, String url, String auth) throws Exception {
        return execute(client, new Request.Builder().url(url).header("Authorization", auth).build());
    }

    private static Reply post(OkHttpClient client, String url, String auth, RequestBody body) throws Exception {
        return execute(client, new Request.Builder().url(url).header("Authorization", auth).post(body).build());
    }

    private static Reply execute(OkHttpClient client, Request request) throws Exception {
        try (var response = client.newCall(request).execute()) {
            return new Reply(response.code(), response.body().string());
        }
    }
}
