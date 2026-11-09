package io.saasforge.sdk.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tenant IP Allowlist 的字面量解析、规范化、匹配与存储约定，供 iam 和网关共用。
 * 不解析主机名、不进行 DNS 查询，IPv4 与 IPv6 仅匹配各自地址族。
 */
public final class TenantIpAllowlist {
    /** 空列表发布为此明确值；缺失值绝不能解释为无限制。 */
    public static final String UNRESTRICTED = "*";

    /** 每个 Tenant 允许保存的最大规则数，按原始条目计数。 */
    public static final int MAX_RULES = 100;

    private final List<Network> networks;

    private TenantIpAllowlist(List<Network> networks) {
        this.networks = List.copyOf(networks);
    }

    /** 解析 IPv4、IPv6 或 CIDR 规则，并清除 CIDR 中的主机位；非法输入抛出 IllegalArgumentException。 */
    public static TenantIpAllowlist parse(List<String> rules) {
        if (rules == null) throw new IllegalArgumentException("Rules are required");
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("At most 100 IP rules are allowed");
        var networks = new ArrayList<Network>();
        for (String rule : rules) networks.add(network(rule));
        return new TenantIpAllowlist(networks);
    }

    /** 从数据库或 Redis 读取已发布值；缺失、空字符串或非法规则抛出 IllegalArgumentException。 */
    public static TenantIpAllowlist decode(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing IP allowlist");
        return parse(UNRESTRICTED.equals(value) ? List.of() : List.of(value.split(",", -1)));
    }

    /** 返回规范化后的规则，顺序与输入一致；IPv6 使用小写并压缩最长的连续零段。 */
    public List<String> rules() {
        return networks.stream().map(Network::rule).toList();
    }

    /** 返回可持久化的发布值；空列表也始终有明确值。 */
    public String encode() {
        return networks.isEmpty() ? UNRESTRICTED : String.join(",", rules());
    }

    /** 判断客户端 IP 字面量是否被允许；启用限制时非法或缺失 IP 一律拒绝。 */
    public boolean allows(String ip) {
        if (networks.isEmpty()) return true;
        try {
            byte[] address = literal(ip);
            return networks.stream().anyMatch(network -> network.contains(address));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static Network network(String value) {
        if (value == null) throw new IllegalArgumentException("IP rule is required");
        String[] parts = value.strip().split("/", -1);
        if (parts.length > 2) throw new IllegalArgumentException("Invalid CIDR");
        byte[] address = literal(parts[0]);
        int prefix = address.length * 8;
        if (parts.length == 2) {
            if (!parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid CIDR prefix");
            prefix = Integer.parseInt(parts[1]);
            if (prefix <= 0 || prefix > address.length * 8) throw new IllegalArgumentException("Invalid CIDR prefix");
        }
        for (int i = 0; i < address.length; i++) {
            address[i] &= (byte) (0xff << Math.max(0, Math.min(8, (i + 1) * 8 - prefix)));
        }
        String rule = format(address) + (parts.length == 2 ? "/" + prefix : "");
        return new Network(address, prefix, rule);
    }

    private static byte[] literal(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("IP is required");
        if (!value.contains(":")) return ipv4(value);
        if (!value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("Invalid IPv6");
        // IPv4 尾段占两个十六进制组；手动转换以保留 IPv4-mapped IPv6 的地址族。
        if (value.contains(".")) {
            int colon = value.lastIndexOf(':');
            byte[] tail = ipv4(value.substring(colon + 1));
            value = value.substring(0, colon + 1) + Integer.toHexString((tail[0] & 255) * 256 + (tail[1] & 255)) + ":"
                    + Integer.toHexString((tail[2] & 255) * 256 + (tail[3] & 255));
        }
        String[] halves = value.split("::", -1);
        if (halves.length > 2) throw new IllegalArgumentException("Invalid IPv6 compression");
        List<Integer> left = groups(halves[0]);
        List<Integer> right = halves.length == 2 ? groups(halves[1]) : List.of();
        int count = left.size() + right.size();
        if ((halves.length == 1 && count != 8) || (halves.length == 2 && count >= 8)) {
            throw new IllegalArgumentException("Invalid IPv6 length");
        }
        byte[] result = new byte[16];
        for (int i = 0; i < left.size(); i++) putGroup(result, i, left.get(i));
        for (int i = 0; i < right.size(); i++) putGroup(result, 8 - right.size() + i, right.get(i));
        return result;
    }

    private static byte[] ipv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4");
        byte[] result = new byte[4];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid IPv4 octet");
            int part = Integer.parseInt(parts[i]);
            if (part > 255) throw new IllegalArgumentException("Invalid IPv4 octet");
            result[i] = (byte) part;
        }
        return result;
    }

    private static List<Integer> groups(String value) {
        if (value.isEmpty()) return List.of();
        var result = new ArrayList<Integer>();
        for (String group : value.split(":", -1)) {
            if (!group.matches("[0-9a-fA-F]{1,4}")) throw new IllegalArgumentException("Invalid IPv6 group");
            result.add(Integer.parseInt(group, 16));
        }
        return result;
    }

    private static void putGroup(byte[] address, int index, int value) {
        address[index * 2] = (byte) (value >>> 8);
        address[index * 2 + 1] = (byte) value;
    }

    private static String format(byte[] address) {
        if (address.length == 4) {
            return (address[0] & 255) + "." + (address[1] & 255) + "." + (address[2] & 255) + "." + (address[3] & 255);
        }
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) groups[i] = (address[i * 2] & 255) * 256 + (address[i * 2 + 1] & 255);
        int bestStart = -1;
        int bestLength = 1;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int end = i;
            while (end < 8 && groups[end] == 0) end++;
            if (end - i > bestLength) {
                bestStart = i;
                bestLength = end - i;
            }
            i = end;
        }
        var result = new StringBuilder();
        for (int i = 0; i < 8; ) {
            if (i == bestStart) {
                result.append("::");
                i += bestLength;
            } else {
                if (!result.isEmpty() && result.charAt(result.length() - 1) != ':') result.append(':');
                result.append(Integer.toHexString(groups[i++]));
            }
        }
        return result.toString();
    }

    private record Network(byte[] address, int prefix, String rule) {
        boolean contains(byte[] candidate) {
            if (address.length != candidate.length) return false;
            byte[] masked = candidate.clone();
            for (int i = 0; i < masked.length; i++) {
                masked[i] &= (byte) (0xff << Math.max(0, Math.min(8, (i + 1) * 8 - prefix)));
            }
            return Arrays.equals(address, masked);
        }
    }
}
