package dev.workspace.qwen.asr;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class Wire implements AutoCloseable {
    static final int PORT = 2370;
    static final String USER = "admin";
    private static final int SOCKET_READ_TIMEOUT_MS = 4_000;
    private static final int LOOPBACK_REQUEST_LINE_TIMEOUT_MS = 10_000;
    private static final int KIKOETA_READ_TIMEOUT_MS = 15_000;
    private final Jobs jobs;
    private final AudioApi api;
    private final byte[] basic;
    private final byte[] openai;
    private final byte[] dash;
    private final Map<InetSocketAddress, ServerSocket> listeners = new LinkedHashMap<>();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final AtomicLong requests = new AtomicLong();
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8));
    private final java.util.concurrent.ExecutorService peers = Executors.newFixedThreadPool(4);
    private volatile AccessPolicy policy;
    private volatile boolean closed;

    record Authentication(String password, String openai, String dashscope) {}

    Wire(Context context, Jobs jobs, AccessPolicy policy) throws IOException {
        this(context, jobs, policy, new Authentication(Secrets.get(context, "password", true),
                Secrets.get(context, "openaiKey", true), Secrets.get(context, "dashKey", true)));
    }

    Wire(Context context, Jobs jobs, AccessPolicy policy, Authentication authentication) throws IOException {
        this.jobs = jobs;
        api = new AudioApi(context, jobs);
        basic = auth("Basic ", Base64.encodeToString((USER + ":" + authentication.password())
                .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        openai = auth("Bearer ", authentication.openai());
        dash = auth("Bearer ", authentication.dashscope());
        rebind(policy);
    }

    private static byte[] auth(String scheme, String secret) {
        return (scheme + secret).getBytes(StandardCharsets.US_ASCII);
    }

    synchronized void rebind(AccessPolicy next) throws IOException {
        var candidates = new LinkedHashMap<InetSocketAddress, ServerSocket>();
        List<InetAddress> addresses = new ArrayList<>();
        addresses.add(InetAddress.getByName("127.0.0.1"));
        var v6 = InetAddress.getByName("::1");
        if (java.net.NetworkInterface.getByInetAddress(v6) != null) addresses.add(v6);
        if (next.lan() != null) addresses.add(next.lan());
        try {
            for (var address : addresses) {
                var endpoint = new InetSocketAddress(address, next.port());
                var existing = listeners.get(endpoint);
                if (existing != null) {
                    candidates.put(endpoint, existing);
                    continue;
                }
                ServerSocket socket = new ServerSocket();
                try {
                    socket.setReuseAddress(true);
                    socket.bind(endpoint, 16);
                    candidates.put(endpoint, socket);
                } catch (IOException error) {
                    close(socket);
                    throw error;
                }
            }
        } catch (IOException error) {
            candidates.forEach((key, socket) -> { if (!listeners.containsKey(key)) close(socket); });
            throw error;
        }
        policy = next;
        // Revocation takes effect on existing and queued connections in addition to new accepts.
        connections.forEach(Wire::close);
        listeners.forEach((key, socket) -> { if (!candidates.containsKey(key)) close(socket); });
        candidates.forEach((key, socket) -> {
            if (!listeners.containsKey(key)) new Thread(() -> accept(socket), "Qwen gateway accept").start();
        });
        listeners.clear();
        listeners.putAll(candidates);
    }

    synchronized List<String> addresses() {
        return listeners.keySet().stream().map(endpoint -> {
            String host = endpoint.getAddress().getHostAddress();
            return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + endpoint.getPort();
        }).collect(java.util.stream.Collectors.toList());
    }

    long requests() { return requests.get(); }
    int pending() { return clients.getQueue().size(); }
    boolean busy() { return !connections.isEmpty(); }

    private void accept(ServerSocket listener) {
        while (!closed && !listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                synchronized (this) {
                    if (closed || listener.isClosed() || !policy.permits(socket.getInetAddress())) {
                        close(socket);
                        continue;
                    }
                    connections.add(socket);
                    try {
                        clients.execute(() -> serve(socket));
                    } catch (java.util.concurrent.RejectedExecutionException error) {
                        connections.remove(socket);
                        close(socket);
                        RuntimeLog.add("WARN", "网关", "", "", "连接队列已满");
                    }
                }
            } catch (IOException ignored) {
                break;
            }
        }
    }

    private enum Protocol { KIKOETA, OPENAI, DASHSCOPE }
    private record Incoming(String method, String target, Protocol protocol, Map<String, String> headers) {}

    private void serve(Socket socket) {
        Protocol protocol = null;
        try (socket) {
            socket.setSoTimeout(socket.getInetAddress().isLoopbackAddress()
                    ? LOOPBACK_REQUEST_LINE_TIMEOUT_MS : SOCKET_READ_TIMEOUT_MS);
            var raw = new BufferedInputStream(socket.getInputStream());
            raw.mark(1);
            try {
                if (raw.read() < 0) return;
            } catch (java.net.SocketTimeoutException idleConnection) {
                return;
            }
            raw.reset();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            InputStream bounded = new java.io.FilterInputStream(raw) {
                private void check() throws IOException {
                    if (System.nanoTime() > deadline) throw new java.net.SocketTimeoutException();
                }
                @Override public int read() throws IOException { check(); return super.read(); }
                @Override public int read(byte[] bytes, int start, int size) throws IOException {
                    check();
                    return super.read(bytes, start, size);
                }
            };
            Response response;
            String stage = "请求行";
            String operation = "";
            String timeoutStage = "";
            try {
                String[] request = line(bounded).split(" ");
                if (request.length != 3 || !request[1].startsWith("/") || !request[2].matches("HTTP/1\\.[01]")) {
                    throw new ApiError(400, "无效的 HTTP 请求");
                }
                String path = request[1].split("\\?", 2)[0];
                protocol = protocol(path);
                operation = protocol == Protocol.KIKOETA ? kikoetaOperation(path) : "";
                socket.setSoTimeout(protocol == Protocol.KIKOETA ? KIKOETA_READ_TIMEOUT_MS : SOCKET_READ_TIMEOUT_MS);
                stage = "请求头";
                var fields = new HashMap<String, String>();
                for (int i = 0; ; i++) {
                    if (i >= 32) throw new ApiError(400, "HTTP 请求头过多");
                    String header = line(bounded);
                    if (header.isEmpty()) break;
                    int split = header.indexOf(':');
                    if (split < 1) throw new ApiError(400, "无效的 HTTP 请求头");
                    String key = header.substring(0, split).toLowerCase(Locale.ROOT);
                    String value = header.substring(split + 1).strip();
                    if (!key.matches("[a-z0-9_-]+") || fields.putIfAbsent(key, value) != null) {
                        throw new ApiError(400, "无效或重复的 HTTP 请求头");
                    }
                }
                stage = "请求体";
                response = route(socket, bounded, raw, new Incoming(request[0], request[1], protocol, fields));
            } catch (ApiError error) {
                response = error(protocol, error.status, error.getMessage());
            } catch (JSONException | IllegalArgumentException error) {
                response = error(protocol, 400, "请求格式不正确");
            } catch (java.net.SocketTimeoutException timeout) {
                timeoutStage = " · " + stage + operation;
                response = error(protocol, 408, "读取" + stage + "超时");
            } catch (EOFException | java.net.SocketException | InterruptedException disconnected) {
                return;
            } catch (Exception error) {
                response = error(protocol, 503, "音频处理失败");
            }
            if (response.status() >= 400) {
                String label = (protocol == null ? "" : protocol + " ") + "HTTP " + response.status();
                RuntimeLog.add("WARN", "网关", "", "", label + timeoutStage);
            }
            send(socket, protocol, response);
        } catch (IOException | JSONException ignored) {
        } finally {
            connections.remove(socket);
            Thread.interrupted();
        }
    }

    private static Protocol protocol(String path) {
        if (path.startsWith("/v1/")) return Protocol.OPENAI;
        if (path.startsWith("/api/v1/services/") || path.startsWith("/api/v1/tasks/")) return Protocol.DASHSCOPE;
        return Protocol.KIKOETA;
    }

    private static String kikoetaOperation(String path) {
        if (path.startsWith("/api/v1/jobs/") && path.endsWith("/events")) return " · 任务事件";
        if (path.equals("/api/v1/jobs")) return " · 任务提交";
        if (path.startsWith("/api/v1/jobs/")) return " · 任务状态或控制";
        if (path.equals("/api/v1/cache")) return " · 缓存列表";
        if (path.startsWith("/api/v1/output/")) return " · 字幕下载";
        return "";
    }

    private Response route(Socket socket, InputStream input, InputStream raw, Incoming request) throws Exception {
        AccessPolicy active = policy;
        byte[] expected = switch (request.protocol()) {
            case KIKOETA -> active.kikoeta() ? basic : null;
            case OPENAI -> active.openai() ? openai : null;
            case DASHSCOPE -> active.dashscope() ? dash : null;
        };
        if (expected == null) throw new ApiError(404, "接口未开启");
        var headers = request.headers();
        if (!MessageDigest.isEqual(expected, headers.getOrDefault("authorization", "").getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiError(401, "授权凭据无效");
        }
        requests.incrementAndGet();
        if (headers.containsKey("transfer-encoding")) throw new ApiError(400, "此接口要求 Content-Length");
        boolean post = request.method().equals("POST");
        if (!post && !request.method().equals("GET")) throw new ApiError(405, "不支持此 HTTP 方法");
        int limit = request.protocol() == Protocol.KIKOETA ? 256 * 1024 : AudioApi.MAX_BODY;
        int size = 0;
        if (post) {
            String length = headers.get("content-length");
            if (length == null) throw new ApiError(411, "缺少 Content-Length");
            if (!length.matches("[0-9]{1,9}")) throw new ApiError(400, "无效的 Content-Length");
            size = Integer.parseInt(length);
            if (size > limit) throw new ApiError(413, "请求体过大");
            if (headers.containsKey("expect")) {
                if (!headers.get("expect").equalsIgnoreCase("100-continue")) throw new ApiError(417, "不支持此 Expect 请求头");
                socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
        }
        if (request.protocol() == Protocol.KIKOETA) {
            byte[] body = readBody(input, size);
            JSONObject json = post ? size == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8)) : null;
            return jobs.route(request.method(), request.target(), json);
        }
        if (!post && request.protocol() == Protocol.OPENAI && request.target().equals("/v1/models")) return api.models();
        boolean dash = request.protocol() == Protocol.DASHSCOPE;
        if (!post || !request.target().equals(dash ? AudioApi.DASH_PATH : "/v1/audio/transcriptions")) {
            throw new ApiError(404, "找不到接口");
        }
        if (dash && ("enable".equalsIgnoreCase(headers.get("x-dashscope-sse"))
                || "enable".equalsIgnoreCase(headers.get("x-dashscope-async")))) {
            throw new ApiError(400, "此接口使用同步响应");
        }
        byte[] body = readBody(input, size);
        socket.setSoTimeout(500);
        long timeout = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        Thread worker = Thread.currentThread();
        final class Peer implements Runnable {
            private boolean finished;
            @Override public void run() {
                try {
                    while (System.nanoTime() < timeout) {
                        synchronized (this) { if (finished) return; }
                        try {
                            raw.read();
                            break; // Each connection accepts a single request.
                        } catch (java.net.SocketTimeoutException waiting) {
                            // Check the deadline and worker state again.
                        }
                    }
                } catch (IOException disconnected) {
                    // A closed peer no longer needs a result.
                }
                synchronized (this) {
                    if (finished) return;
                    worker.interrupt();
                    close(socket);
                }
            }
            synchronized void finish() { finished = true; }
        }
        var peer = new Peer();
        peers.execute(peer);
        try {
            return api.recognize(dash, headers.getOrDefault("content-type", ""), body, active, () -> {
                if (socket.isClosed() || Thread.currentThread().isInterrupted() || System.nanoTime() > timeout) {
                    throw new java.util.concurrent.CancellationException();
                }
            });
        } finally {
            peer.finish();
        }
    }

    private static byte[] readBody(InputStream input, int size) throws IOException {
        var bytes = new byte[size];
        new java.io.DataInputStream(input).readFully(bytes);
        return bytes;
    }

    private static Response error(Protocol protocol, int status, String message) throws JSONException {
        return AudioApi.error(protocol == Protocol.DASHSCOPE, protocol == Protocol.OPENAI, status, message);
    }

    private static void send(Socket socket, Protocol protocol, Response response) throws IOException {
        String status = switch (response.status()) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 411 -> "Length Required";
            case 413 -> "Content Too Large";
            case 417 -> "Expectation Failed";
            case 429 -> "Too Many Requests";
            default -> "Service Unavailable";
        };
        String auth = protocol == Protocol.KIKOETA ? "Basic realm=\"Qwen ASR\"" : "Bearer";
        var output = socket.getOutputStream();
        output.write(("HTTP/1.1 " + response.status() + " " + status + "\r\n"
                + "Content-Type: " + response.contentType() + "\r\n"
                + "Content-Length: " + response.body().length + "\r\n"
                + (response.status() == 401 ? "WWW-Authenticate: " + auth + "\r\n" : "")
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(response.body());
        output.flush();
    }

    @Override public synchronized void close() {
        closed = true;
        listeners.values().forEach(Wire::close);
        connections.forEach(Wire::close);
        connections.clear();
        clients.shutdownNow();
        peers.shutdownNow();
    }

    private static void close(java.io.Closeable socket) {
        try { socket.close(); } catch (IOException ignored) {}
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
