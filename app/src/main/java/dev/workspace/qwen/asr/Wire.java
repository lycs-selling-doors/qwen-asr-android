package dev.workspace.qwen.asr;

import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class Wire implements AutoCloseable {
    static final int PORT = 2370;
    static final String USER = "admin";
    private final ServerSocket listener;
    private final Jobs jobs;
    private final byte[] auth;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16));

    Wire(String password, Jobs jobs) throws IOException {
        this.jobs = jobs;
        auth = ("Basic " + Base64.encodeToString((USER + ":" + password).getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP)).getBytes(StandardCharsets.US_ASCII);
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        try {
            listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16);
        } catch (IOException error) {
            listener.close();
            throw error;
        }
        new Thread(this::accept, "Kikoeta HTTP").start();
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                try {
                    clients.execute(() -> serve(socket));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    socket.close();
                }
            } catch (IOException ignored) {
                break;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            Response response;
            try {
                response = parse(input);
            } catch (ApiError error) {
                response = Response.json(error.status, new JSONObject().put("error", error.getMessage()));
            } catch (JSONException | IllegalArgumentException error) {
                response = Response.json(400, new JSONObject().put("error", "请求格式不正确"));
            }
            String status = switch (response.status()) {
                case 200 -> "OK";
                case 201 -> "Created";
                case 400 -> "Bad Request";
                case 401 -> "Unauthorized";
                case 404 -> "Not Found";
                case 409 -> "Conflict";
                case 411 -> "Length Required";
                case 413 -> "Content Too Large";
                default -> "Service Unavailable";
            };
            var output = socket.getOutputStream();
            output.write(("HTTP/1.1 " + response.status() + " " + status + "\r\n"
                    + "Content-Type: " + response.contentType() + "\r\n"
                    + "Content-Length: " + response.body().length + "\r\n"
                    + (response.status() == 401 ? "WWW-Authenticate: Basic realm=\"Qwen ASR\"\r\n" : "")
                    + "Cache-Control: no-store\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(response.body());
            output.flush();
        } catch (IOException | JSONException ignored) {
            // A client may disconnect before its response is written.
        }
    }

    private Response parse(InputStream input) throws IOException, JSONException {
        String[] request = line(input).split(" ");
        if (request.length != 3 || !request[1].startsWith("/") || !request[2].startsWith("HTTP/1.")) {
            throw new ApiError(400, "无效的 HTTP 请求");
        }
        int length = -1;
        String authorization = "";
        for (int i = 0; ; i++) {
            if (i >= 64) throw new ApiError(400, "HTTP 请求头过多");
            String header = line(input);
            if (header.isEmpty()) break;
            int split = header.indexOf(':');
            if (split < 1) throw new ApiError(400, "无效的 HTTP 请求头");
            String value = header.substring(split + 1).strip();
            switch (header.substring(0, split).toLowerCase(Locale.ROOT)) {
                case "authorization" -> authorization = value;
                case "content-length" -> {
                    if (length != -1) throw new ApiError(400, "重复的 Content-Length");
                    if (!value.matches("[0-9]{1,9}")) throw new ApiError(400, "无效的 Content-Length");
                    length = Integer.parseInt(value);
                }
                case "transfer-encoding" -> throw new ApiError(400, "此接口要求 Content-Length");
            }
        }
        if (!MessageDigest.isEqual(auth, authorization.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiError(401, "请使用伴侣应用显示的账号和密码");
        }
        if (length > 256 * 1024) throw new ApiError(413, "任务请求体过大");
        JSONObject json = null;
        if ("POST".equals(request[0])) {
            if (length < 0) throw new ApiError(411, "缺少 Content-Length");
            byte[] body = new byte[length];
            new java.io.DataInputStream(input).readFully(body);
            json = length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
        }
        return jobs.route(request[0], request[1], json);
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int i = 0; i < 8192; i++) {
            int c = input.read();
            if (c < 0) throw new EOFException();
            if (c == '\n') {
                byte[] bytes = result.toByteArray();
                int size = bytes.length;
                if (size == 0 || bytes[size - 1] != '\r') throw new ApiError(400, "无效的 HTTP 换行");
                return new String(bytes, 0, size - 1, StandardCharsets.US_ASCII);
            }
            result.write(c);
        }
        throw new ApiError(400, "HTTP 请求行过长");
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("响应体过大");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    @Override public void close() {
        try { listener.close(); } catch (IOException ignored) {}
        clients.shutdown();
    }

    record Response(int status, String contentType, byte[] body) {
        static Response json(int status, JSONObject value) {
            return new Response(status, "application/json; charset=utf-8",
                    value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static final class ApiError extends IOException {
        final int status;
        ApiError(int status, String message) { super(message); this.status = status; }
    }
}
