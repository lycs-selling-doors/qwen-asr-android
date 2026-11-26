package dev.workspace.qwen.asr;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import static dev.workspace.qwen.asr.Wire.ApiError;
import static dev.workspace.qwen.asr.Wire.Response;

final class AudioApi {
    static final String DASH_PATH = "/api/v1/services/aigc/multimodal-generation/generation";
    static final int MAX_AUDIO = 8 * 1024 * 1024;
    static final int MAX_BODY = 12 * 1024 * 1024;
    private final Context context;
    private final Jobs jobs;
    private final Semaphore slots = new Semaphore(2);

    AudioApi(Context context, Jobs jobs) {
        this.context = context;
        this.jobs = jobs;
    }

    Response models() throws JSONException {
        JSONArray items = new JSONArray();
        Models repository = new Models(context);
        for (Models.Card card : Models.CARDS) {
            if (repository.installed(card) != null) items.put(model(card.id()));
        }
        Inference engine = jobs.engine();
        if (engine != null && engine.modelId().equals("external")) items.put(model("external"));
        return Response.json(200, new JSONObject().put("object", "list").put("data", items));
    }

    private static JSONObject model(String id) throws JSONException {
        return new JSONObject().put("object", "model").put("id", id).put("created", 0).put("owned_by", "local");
    }

    Response recognize(boolean dash, String type, byte[] body, AccessPolicy access, Runnable checkpoint) throws Exception {
        if (!slots.tryAcquire()) throw new ApiError(429, "识别队列已满，请稍后重试");
        File audio = null;
        try {
            audio = File.createTempFile("gateway-", ".audio", context.getCacheDir());
            if (dash) return dash(body, audio, access, checkpoint);
            return openai(type, body, audio, checkpoint);
        } finally {
            if (audio != null) audio.delete();
            slots.release();
        }
    }

    private Response openai(String type, byte[] body, File audio, Runnable checkpoint) throws Exception {
        var parts = Multipart.parse(type, body);
        if (!Set.of("file", "model", "language", "response_format", "stream").containsAll(parts.keySet())) {
            throw new ApiError(400, "请求包含当前版本未支持的转写参数");
        }
        if (!field(parts, "stream", body, "false").equals("false")) throw new ApiError(400, "此接口使用同步响应");
        String format = field(parts, "response_format", body, "json");
        if (!format.equals("json") && !format.equals("text")) throw new ApiError(400, "response_format 支持 json 和 text");
        String id = field(parts, "model", body, "");
        if (id.isBlank()) throw new ApiError(400, "缺少 model");
        Inference engine = requireModel(id);
        Multipart.Part file = parts.get("file");
        if (file == null || file.end() <= file.start()) throw new ApiError(400, "缺少音频文件");
        if (file.end() - file.start() > MAX_AUDIO) throw new ApiError(413, "音频超过 8 MiB");
        try (var stream = Files.newOutputStream(audio.toPath())) {
            stream.write(body, file.start(), file.end() - file.start());
        }
        TranslationResult result = transcribe(engine, audio, language(field(parts, "language", body, "auto")), checkpoint);
        return format.equals("json") ? Response.json(200, new JSONObject().put("text", result.text))
                : new Response(200, "text/plain; charset=utf-8", result.text.getBytes(StandardCharsets.UTF_8));
    }

    private Response dash(byte[] body, File audio, AccessPolicy access, Runnable checkpoint) throws Exception {
        JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        String id = json.getString("model");
        if (id.isBlank()) throw new ApiError(400, "缺少 model");
        if (!access.dashAlias().isEmpty() && id.equals(access.dashAlias())) {
            Models.Pair selected = new Models(context).selected(DictationService.prefs(context).getString("modelId", ""));
            Inference active = jobs.engine();
            id = active != null ? active.modelId() : selected != null ? selected.card().id() : id;
        }
        Inference engine = requireModel(id);
        JSONObject parameters = json.optJSONObject("parameters");
        if (json.has("parameters") && parameters == null) throw new ApiError(400, "parameters 必须为对象");
        if (parameters != null && !only(parameters, Set.of("asr_options", "result_format"))) {
            throw new ApiError(400, "parameters 含有未支持的参数");
        }
        if (parameters != null && !parameters.optString("result_format", "message").equals("message")) {
            throw new ApiError(400, "result_format 支持 message");
        }
        JSONObject options = parameters == null ? null : parameters.optJSONObject("asr_options");
        if (parameters != null && parameters.has("asr_options") && options == null) {
            throw new ApiError(400, "asr_options 必须为对象");
        }
        if (options != null && (!only(options, Set.of("language", "enable_itn"))
                || (options.has("enable_itn") && !Boolean.FALSE.equals(options.get("enable_itn"))))) {
            throw new ApiError(400, "此模型网关支持 language 和 enable_itn=false");
        }
        String language = language(options == null ? "auto" : options.optString("language", "auto"));
        JSONArray messages = json.getJSONObject("input").getJSONArray("messages");
        if (messages.length() != 1 || !messages.getJSONObject(0).getString("role").equals("user")) {
            throw new ApiError(400, "需要一条 user 音频消息");
        }
        JSONArray content = messages.getJSONObject(0).getJSONArray("content");
        if (content.length() != 1) throw new ApiError(400, "每次提交一条音频内容");
        String value = content.getJSONObject(0).getString("audio");
        if (value.startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma < 0 || !value.substring(0, comma).matches("data:audio/[a-zA-Z0-9.+-]+;base64")) {
                throw new ApiError(400, "请使用音频 Base64 Data URI");
            }
            byte[] decoded = Base64.getDecoder().decode(value.substring(comma + 1));
            if (decoded.length > MAX_AUDIO) throw new ApiError(413, "音频超过 8 MiB");
            if (decoded.length == 0) throw new ApiError(400, "音频为空");
            Files.write(audio.toPath(), decoded);
        } else {
            var prefs = DictationService.prefs(context);
            boolean allowPrivate = prefs.getBoolean("allowPrivateAudio", true);
            var trusted = AudioFetch.parseOrigins(prefs.getString("kikoetaLanSource", ""));
            AudioFetch.download(AudioFetch.url(value), Map.of(), trusted, allowPrivate, audio, MAX_AUDIO, 60, checkpoint);
        }
        TranslationResult result = transcribe(engine, audio, language, checkpoint);
        return Response.json(200, new JSONObject().put("request_id", UUID.randomUUID().toString())
                .put("output", new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                        .put("finish_reason", "stop").put("message", new JSONObject().put("role", "assistant")
                                .put("content", new JSONArray().put(new JSONObject().put("text", result.text)))))))
                .put("usage", new JSONObject().put("seconds", result.millis / 1000.0)));
    }

    private Inference requireModel(String id) throws ApiError {
        Inference current = jobs.engine();
        if (current != null && current.modelId().equals(id)) return current;
        Models.Pair pair = new Models(context).selected(id);
        if (pair == null && !(id.equals("external")
                && !DictationService.prefs(context).getString("asr", "").isBlank())) {
            throw new ApiError(404, "模型尚未安装或 ID 无效");
        }
        if (current == null) throw new ApiError(503, "模型尚未预热，请在服务页加载");
        throw new ApiError(409, "模型已安装，请在模型页切换后重新加载");
    }

    private static String field(Map<String, Multipart.Part> parts, String name, byte[] body, String fallback)
            throws ApiError {
        var part = parts.get(name);
        return part == null ? fallback : part.text(body);
    }

    private static String language(String code) throws ApiError {
        if (!code.matches("auto|[a-z]{2,3}")) throw new ApiError(400, "language 无效");
        return code;
    }

    private static boolean only(JSONObject object, Set<String> allowed) {
        var keys = object.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) return false;
        return true;
    }

    private record TranslationResult(String text, long millis) {}

    private TranslationResult transcribe(Inference engine, File audio, String language, Runnable checkpoint)
            throws Exception {
        StringBuilder text = new StringBuilder();
        long[] end = {0};
        try {
            String decoder = DictationService.prefs(context).getString("audioDecoder", "auto");
            Pcm.decode(audio, 8, decoder, checkpoint, (start, pcm) -> {
                end[0] = Pcm.millis(start + pcm.length / 2);
                if (end[0] > 300_000) throw new ApiError(413, "音频超过 5 分钟");
                checkpoint.run();
                if (Pcm.silent(pcm)) return;
                String value;
                try {
                    value = engine.transcribe(pcm, language, false);
                } catch (Inference.HttpError error) {
                    throw new ApiError(503, error.getMessage());
                } catch (java.io.InterruptedIOException error) {
                    throw new ApiError(503, "推理服务响应超时");
                } catch (IOException error) {
                    throw new ApiError(503, "无法读取推理服务响应");
                }
                if (!value.isBlank()) {
                    if (text.length() != 0) text.append('\n');
                    text.append(value);
                }
            });
        } catch (ApiError error) {
            throw error;
        } catch (IllegalArgumentException | IOException error) {
            throw new ApiError(400, "无法解码音频或读取音频流");
        }
        if (end[0] == 0) throw new ApiError(400, "未读到有效音频样本");
        return new TranslationResult(text.toString(), end[0]);
    }

    static Response error(boolean dash, boolean openai, int status, String message) throws JSONException {
        String code = switch (status) {
            case 401 -> "invalid_api_key";
            case 404 -> "model_not_found";
            case 413 -> "file_too_large";
            case 429 -> "rate_limit_exceeded";
            case 503 -> "model_unavailable";
            default -> "invalid_request";
        };
        if (dash) return Response.json(status, new JSONObject().put("request_id", UUID.randomUUID().toString())
                .put("code", switch (status) {
                    case 401 -> "InvalidApiKey";
                    case 404 -> "ModelNotFound";
                    case 429 -> "Throttling";
                    case 503 -> "ServiceUnavailable";
                    default -> "InvalidParameter";
                }).put("message", message));
        if (openai) return Response.json(status, new JSONObject().put("error", new JSONObject()
                .put("type", status >= 500 ? "server_error" : "invalid_request_error")
                .put("code", code).put("message", message).put("param", JSONObject.NULL)));
        return Response.json(status, new JSONObject().put("error", message));
    }
}
