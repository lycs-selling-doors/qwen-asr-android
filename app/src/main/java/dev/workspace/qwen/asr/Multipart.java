package dev.workspace.qwen.asr;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class Multipart {
    private static final Pattern BOUNDARY = Pattern.compile(
            "(?:^|;)\\s*boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))\\s*(?:;|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("(?:^|;)\\s*name=\"([a-zA-Z_]+)\"(?:\\s*;|\\s*$)");
    record Part(int start, int end) {
        String text(byte[] body) throws Wire.ApiError {
            if (end - start > 512) throw new Wire.ApiError(400, "multipart 文本字段过长");
            return new String(body, start, end - start, StandardCharsets.UTF_8);
        }
    }

    static Map<String, Part> parse(String type, byte[] body) throws Wire.ApiError {
        if (!type.split(";", 2)[0].strip().equalsIgnoreCase("multipart/form-data")) {
            throw new Wire.ApiError(400, "需要 multipart/form-data");
        }
        var matched = BOUNDARY.matcher(type);
        if (!matched.find()) throw invalid();
        String token = matched.group(1) == null ? matched.group(2) : matched.group(1);
        if (!token.matches("[A-Za-z0-9'()+_,./:=? -]{1,70}")) throw invalid();
        byte[] marker = ("--" + token).getBytes(StandardCharsets.US_ASCII);
        byte[] separator = ("\r\n--" + token).getBytes(StandardCharsets.US_ASCII);
        byte[] headersEnd = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        if (!at(body, marker, 0)) throw invalid();
        Map<String, Part> parts = new HashMap<>();
        int cursor = marker.length;
        while (true) {
            if (at(body, new byte[]{'-', '-'}, cursor)) {
                cursor += 2;
                if (cursor == body.length || (cursor == body.length - 2 && crlf(body, cursor))) return parts;
                throw invalid();
            }
            if (!crlf(body, cursor) || parts.size() >= 12) throw invalid();
            int start = cursor + 2;
            int headerEnd = find(body, headersEnd, start, Math.min(body.length, start + 8192));
            if (headerEnd < 0) throw invalid();
            String name = null;
            for (String header : new String(body, start, headerEnd - start, StandardCharsets.US_ASCII).split("\r\n")) {
                int colon = header.indexOf(':');
                if (colon <= 0) throw invalid();
                if (!header.substring(0, colon).toLowerCase(Locale.ROOT).equals("content-disposition")) continue;
                String value = header.substring(colon + 1).strip();
                var field = NAME.matcher(value);
                if (name != null || !value.startsWith("form-data;") || !field.find()) throw invalid();
                name = field.group(1);
            }
            if (name == null || parts.containsKey(name)) throw invalid();
            int content = headerEnd + headersEnd.length;
            int next = content;
            do {
                next = find(body, separator, next, body.length);
                if (next < 0) throw invalid();
                cursor = next + separator.length;
                if (crlf(body, cursor) || at(body, new byte[]{'-', '-'}, cursor)) break;
                next++;
            } while (true);
            parts.put(name, new Part(content, next));
        }
    }

    private static boolean crlf(byte[] body, int index) {
        return index + 1 < body.length && body[index] == '\r' && body[index + 1] == '\n';
    }

    private static boolean at(byte[] body, byte[] token, int index) {
        if (index > body.length - token.length) return false;
        for (int i = 0; i < token.length; i++) if (token[i] != body[index + i]) return false;
        return true;
    }

    private static int find(byte[] body, byte[] token, int start, int end) {
        for (int i = start; i <= end - token.length; i++) if (at(body, token, i)) return i;
        return -1;
    }

    private static Wire.ApiError invalid() { return new Wire.ApiError(400, "multipart 边界或字段格式无效"); }
}
