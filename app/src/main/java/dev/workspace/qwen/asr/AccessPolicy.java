package dev.workspace.qwen.asr;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

record AccessPolicy(int port, InetAddress lan, Mode mode, List<Prefix> allowed,
                    boolean kikoeta, boolean openai, boolean dashscope, String dashAlias) {
    enum Mode { LOCAL, ALLOWLIST, OPEN }

    static AccessPolicy parse(int port, String bind, Mode mode, String ranges, boolean kikoeta,
                              boolean openai, boolean dashscope, String dashAlias) throws java.io.IOException {
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("端口范围为 1024–65535");
        InetAddress address = mode == Mode.LOCAL ? null : numeric(bind.strip());
        if (address != null && (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
                || address.isLinkLocalAddress() || NetworkInterface.getByInetAddress(address) == null)) {
            throw new IllegalArgumentException("请选择本机接口的非回环 IP");
        }
        List<Prefix> prefixes = new ArrayList<>();
        if (mode == Mode.ALLOWLIST) {
            for (String token : ranges.strip().split("[\\s,]+")) if (!token.isEmpty()) prefixes.add(Prefix.parse(token));
        }
        if (!dashAlias.isEmpty() && !dashAlias.matches("[a-zA-Z0-9_.-]{1,80}")) {
            throw new IllegalArgumentException("模型别名格式无效");
        }
        return new AccessPolicy(port, address, mode, List.copyOf(prefixes), kikoeta, openai, dashscope, dashAlias);
    }

    boolean permits(InetAddress client) {
        return client.isLoopbackAddress() || mode == Mode.OPEN
                || (mode == Mode.ALLOWLIST && allowed.stream().anyMatch(prefix -> prefix.contains(client)));
    }

    static InetAddress numeric(String value) throws UnknownHostException {
        if (value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+")) return InetAddress.getByName(value);
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new UnknownHostException("请填写数字 IP");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(parts[i]) > 255) {
                throw new UnknownHostException("IPv4 地址无效");
            }
            bytes[i] = (byte) Integer.parseInt(parts[i]);
        }
        return InetAddress.getByAddress(bytes);
    }

    record Prefix(byte[] address, int bits) {
        static Prefix parse(String value) throws UnknownHostException {
            String[] parts = value.split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException("CIDR 格式无效");
            byte[] address = numeric(parts[0]).getAddress();
            int total = address.length * 8;
            if (parts.length == 2 && !parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException("CIDR 前缀无效");
            int bits = parts.length == 1 ? total : Integer.parseInt(parts[1]);
            if (total == 32 && parts[0].contains(":") && parts.length == 2) bits -= 96;
            if (bits < 0 || bits > total) throw new IllegalArgumentException("CIDR 前缀无效");
            return new Prefix(address, bits);
        }

        boolean contains(InetAddress peer) {
            byte[] source = peer.getAddress();
            if (source.length != address.length) return false;
            for (int i = 0; i < bits / 8; i++) if (source[i] != address[i]) return false;
            int partial = bits % 8;
            return partial == 0 || ((source[bits / 8] ^ address[bits / 8]) & (0xff << (8 - partial))) == 0;
        }
    }
}
