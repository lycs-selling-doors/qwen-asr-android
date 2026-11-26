package dev.workspace.qwen.asr;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

interface Translator extends AutoCloseable {
    record Request(long segmentId, String text, String previous, String source, String target) {}
    String translate(Request request, int timeout) throws Exception;
    @Override default void close() {}

    class LocalError extends IOException {
        LocalError(String message) { super(message); }
    }

    final class LanguageUndetermined extends LocalError {
        LanguageUndetermined() { super("无法判定片段语种，请手动选择源语言"); }
    }

    static String language(String value) {
        return switch (value) {
            case "简体中文", "中文", "zh-CN", "zh-Hans" -> "zh";
            case "英语", "en-US", "en-GB" -> "en";
            case "日语", "ja-JP" -> "ja";
            default -> value;
        };
    }

    record Online(String url, String key, String model) implements Translator {
        @Override public String translate(Request request, int timeout) throws Exception {
            String instructions = "Translate the transcript into " + request.target()
                    + ". Preserve its meaning and return only the translated transcript.";
            return Inference.chat(Inference.baseUrl(url), key, model, new JSONArray()
                    .put(new JSONObject().put("role", "system").put("content", instructions))
                    .put(new JSONObject().put("role", "user").put("content", request.text())), timeout).trim();
        }
    }

    final class Offline implements Translator {
        private final com.google.mlkit.nl.languageid.LanguageIdentifier identifier = LanguageIdentification.getClient();

        static List<String> required(String source, String target) {
            String normalized = language(target);
            if (language(source).equals(normalized)) return List.of();
            java.util.Set<String> codes = new java.util.LinkedHashSet<>();
            if (source.equals("auto")) codes.addAll(List.of("ja", "zh", "en"));
            else codes.add(language(source));
            codes.add(normalized);
            codes.remove("en"); // ML Kit bundles English.
            return List.copyOf(codes);
        }

        static Set<String> installed() throws Exception {
            var models = Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel.class),
                    10, TimeUnit.SECONDS);
            java.util.HashSet<String> languages = new java.util.HashSet<>();
            for (var model : models) languages.add(model.getLanguage());
            return Set.copyOf(languages);
        }

        static void download(String code, boolean mobile) throws Exception {
            var conditions = new DownloadConditions.Builder();
            if (!mobile) conditions.requireWifi();
            Tasks.await(RemoteModelManager.getInstance().download(model(code), conditions.build()), 20, TimeUnit.MINUTES);
        }

        private static TranslateRemoteModel model(String code) {
            return new TranslateRemoteModel.Builder(code).build();
        }

        @Override public String translate(Request request, int timeout) throws Exception {
            String source = language(request.source);
            String target = language(request.target);
            if (source.equals("auto")) {
                source = Tasks.await(identifier.identifyLanguage(request.text), timeout, TimeUnit.MILLISECONDS);
                if (source.equals("und") && !request.previous.isBlank()) {
                    source = Tasks.await(identifier.identifyLanguage(request.previous), timeout, TimeUnit.MILLISECONDS);
                }
                if (source.equals("und")) throw new LanguageUndetermined();
            }
            if (source.equals(target)) return request.text;
            if (TranslateLanguage.fromLanguageTag(source) == null || TranslateLanguage.fromLanguageTag(target) == null) {
                throw new LocalError("端侧翻译暂不支持此语种");
            }
            if (!installed().containsAll(required(source, target))) throw new LocalError("缺少离线翻译包");
            var client = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(source).setTargetLanguage(target).build());
            try {
                return Tasks.await(client.translate(request.text), timeout, TimeUnit.MILLISECONDS);
            } finally {
                client.close();
            }
        }

        @Override public void close() { identifier.close(); }
    }
}
