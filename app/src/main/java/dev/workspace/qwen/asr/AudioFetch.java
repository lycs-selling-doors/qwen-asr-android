package dev.workspace.qwen.asr;

import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class AudioFetch {
    private static final int MAX_REDIRECTS = 10;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    static URI url(String value) {
        if (value == null) throw new IllegalArgumentException("无效的音频 URL");
        String trimmed = value.strip();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("无效的音频 URL");
        HttpUrl parsed = HttpUrl.parse(trimmed);
        if (parsed == null) throw new IllegalArgumentException("无效的音频 URL");
        String scheme = parsed.scheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || parsed.host().isBlank()
                || !parsed.username().isEmpty()
                || !parsed.password().isEmpty()) {
            throw new IllegalArgumentException("无效的音频 URL");
        }
        return parsed.newBuilder().fragment(null).build().uri();
    }

    static List<URI> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<URI> origins = new ArrayList<>();
        for (String token : raw.strip().split("[\\s,]+")) {
            if (token.isEmpty()) continue;
            origins.add(url(token));
        }
        return List.copyOf(origins);
    }

    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        if (bytes.length == 16) {
            // Only global unicast. Exclude tunneling and documentation prefixes.
            return (first & 0xe0) == 0x20 && !(first == 0x20 && (second == 2
                    || (second == 1 && ((bytes[2] & 255) < 2 || ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)))));
        }
        return first > 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 192 && (second == 0 || second == 2 || second == 88))
                && !(first == 198 && (second == 18 || second == 19 || (second == 51 && (bytes[2] & 255) == 100)))
                && !(first == 203 && second == 0 && (bytes[2] & 255) == 113);
    }

    /**
     * Clash / Mihomo / sing-box / Surge TUN Fake-IP pools (RFC 2544 198.18.0.0/15 and IPv6 fc00::/18).
     */
    static boolean isFakeIp(InetAddress address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        if (bytes.length == 4) {
            return first == 198 && (second == 18 || second == 19);
        }
        if (bytes.length == 16) {
            return first == 0xfc && second == 0x00 && (bytes[2] & 0xc0) == 0x00;
        }
        return false;
    }

    /**
     * Private LAN (RFC 1918, IPv6 ULA fc00::/7) and carrier/overlay CGNAT (Tailscale 100.64.0.0/10).
     */
    static boolean privateLan(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isSiteLocalAddress()) return true;
        if (bytes.length == 4) {
            int first = bytes[0] & 255;
            int second = bytes[1] & 255;
            return first == 100 && second >= 64 && second <= 127;
        }
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    static boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 255;
            if (first == 0 || first >= 224) return true;
        }
        return false;
    }

    static boolean isAllowedAddress(InetAddress address, boolean allowPrivate, boolean isLocalInterface) {
        if (isLocalInterface || isForbiddenAddress(address)) return false;
        if (publicAddress(address) || isFakeIp(address)) return true;
        return allowPrivate && privateLan(address);
    }

    static List<InetAddress> filterAuthorized(List<InetAddress> resolved, boolean allowPrivate,
                                              java.util.function.Predicate<InetAddress> localInterfaceCheck)
            throws AddressBlockedException {
        List<InetAddress> allowed = new ArrayList<>(resolved.size());
        boolean sawPrivate = false;
        boolean sawLocalOrLoopback = false;
        for (InetAddress address : resolved) {
            boolean localInterface = localInterfaceCheck.test(address);
            if (isAllowedAddress(address, allowPrivate, localInterface)) {
                allowed.add(address);
            } else if (!localInterface && !isForbiddenAddress(address) && privateLan(address)) {
                sawPrivate = true;
            } else {
                sawLocalOrLoopback = true;
            }
        }
        if (!allowed.isEmpty()) return List.copyOf(allowed);
        if (sawPrivate) {
            throw new AddressBlockedException("private-lan",
                    "音频来源指向内网地址，请在「设置 → 服务配置」中开启局域网音频来源或添加来源白名单");
        }
        if (sawLocalOrLoopback) {
            throw new AddressBlockedException("local-restricted",
                    "音频来源指向本机或受限链路地址，已拒绝访问");
        }
        throw new AddressBlockedException("unroutable", "音频地址解析结果不可访问或未获授权");
    }

    static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && port(first) == port(second);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    private static InetAddress parseNumericLiteral(String host) {
        if (host == null || host.isBlank()) return null;
        String clean = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return AccessPolicy.numeric(clean);
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private static boolean isLocalInterface(InetAddress address) {
        try {
            return NetworkInterface.getByInetAddress(address) != null;
        } catch (SocketException error) {
            return false;
        }
    }

    private static Proxy selectProxy(URI uri, InetAddress numericHost) {
        if (numericHost != null && privateLan(numericHost)) {
            return Proxy.NO_PROXY;
        }
        try {
            ProxySelector selector = ProxySelector.getDefault();
            if (selector == null) return Proxy.NO_PROXY;
            List<Proxy> proxies = selector.select(uri);
            if (proxies == null) return Proxy.NO_PROXY;
            for (Proxy proxy : proxies) {
                if (proxy != null && proxy.type() != Proxy.Type.DIRECT) {
                    return proxy;
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back to direct connection if system ProxySelector fails.
        }
        return Proxy.NO_PROXY;
    }

    static void download(URI uri, Map<String, String> headers, URI trustedLan, File output, long limit,
                         int timeoutSeconds, Runnable checkpoint) throws IOException {
        download(uri, headers, trustedLan == null ? List.of() : List.of(trustedLan), false,
                output, limit, timeoutSeconds, checkpoint);
    }

    static void download(URI uri, Map<String, String> headers, List<URI> trustedOrigins, boolean allowPrivateLan,
                         File output, long limit, int timeoutSeconds, Runnable checkpoint) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        URI original = uri;
        boolean sendHeaders = true;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            checkpoint.run();
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) throw new Wire.ApiError(408, "下载音频超时");

            String host = uri.getHost();
            if (host == null || host.isBlank()) throw new Wire.ApiError(400, "无效的音频地址");
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (lowerHost.equals("localhost") || lowerHost.endsWith(".localhost")) {
                RuntimeLog.add("WARN", "音频下载", "", "", "拒绝访问本机域名");
                throw new Wire.ApiError(400, "音频来源指向本机回环地址，已拒绝访问");
            }

            URI current = uri;
            boolean allowPrivateForHop = allowPrivateLan
                    || trustedOrigins.stream().anyMatch(origin -> sameOrigin(origin, current));

            InetAddress numericHost = parseNumericLiteral(host);
            if (numericHost != null) {
                try {
                    filterAuthorized(List.of(numericHost), allowPrivateForHop, AudioFetch::isLocalInterface);
                } catch (AddressBlockedException blocked) {
                    RuntimeLog.add("WARN", "音频下载", "", "", "拒绝访问目标地址（" + blocked.category + "）");
                    throw new Wire.ApiError(400, blocked.getMessage());
                }
            }

            Proxy proxy = selectProxy(uri, numericHost);
            OkHttpClient.Builder clientBuilder = HTTP.newBuilder()
                    .proxy(proxy)
                    .callTimeout(remaining, TimeUnit.MILLISECONDS)
                    .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS));

            if (proxy == Proxy.NO_PROXY) {
                Dns validatingDns = hostname -> {
                    List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
                    return filterAuthorized(addresses, allowPrivateForHop, AudioFetch::isLocalInterface);
                };
                clientBuilder.dns(validatingDns);
            }

            OkHttpClient client = clientBuilder.build();
            Request.Builder request = new Request.Builder()
                    .url(uri.toString())
                    .header("Accept-Encoding", "identity");
            if (sendHeaders) headers.forEach(request::header);
            var call = client.newCall(request.build());
            try (var response = call.execute()) {
                int status = response.code();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) {
                        throw new Wire.ApiError(400, "音频重定向缺少地址");
                    }
                    HttpUrl base = HttpUrl.get(uri);
                    HttpUrl resolved = base.resolve(location.strip());
                    if (resolved == null) throw new Wire.ApiError(400, "无效的音频重定向地址");
                    URI next = url(resolved.toString());
                    if (uri.getScheme().equalsIgnoreCase("https") && !next.getScheme().equalsIgnoreCase("https")) {
                        throw new Wire.ApiError(400, "音频重定向降低了连接安全性");
                    }
                    sendHeaders &= sameOrigin(original, next);
                    uri = next;
                    continue;
                }
                if (status == 206) throw new Wire.ApiError(400, "音频服务器只返回了部分文件");
                if (!response.isSuccessful() || response.body() == null) {
                    throw new Wire.ApiError(400, "音频服务器返回 HTTP " + status);
                }
                if (response.body().contentLength() > limit) throw new Wire.ApiError(413, "音频文件过大");
                try (var input = response.body().byteStream(); var stream = Files.newOutputStream(output.toPath())) {
                    long total = 0;
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkpoint.run();
                        total += count;
                        if (total > limit) throw new Wire.ApiError(413, "音频文件过大");
                        stream.write(buffer, 0, count);
                    }
                }
                return;
            } catch (AddressBlockedException blocked) {
                RuntimeLog.add("WARN", "音频下载", "", "", "拒绝访问音频目标（" + blocked.category + "）");
                throw new Wire.ApiError(400, blocked.getMessage());
            } catch (java.io.InterruptedIOException error) {
                checkpoint.run();
                throw new Wire.ApiError(408, "下载音频超时");
            } catch (UnknownHostException error) {
                RuntimeLog.add("WARN", "音频下载", "", "", "域名解析失败");
                throw new Wire.ApiError(400, "无法解析音频域名 " + host + "，请检查网络连接或代理设置");
            } finally {
                call.cancel();
                client.connectionPool().evictAll();
            }
        }
        throw new Wire.ApiError(400, "音频重定向次数过多");
    }

    static final class AddressBlockedException extends UnknownHostException {
        final String category;

        AddressBlockedException(String category, String message) {
            super(message);
            this.category = category;
        }
    }
}
