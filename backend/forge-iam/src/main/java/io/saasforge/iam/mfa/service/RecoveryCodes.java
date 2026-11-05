package io.saasforge.iam.mfa.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 生成 Recovery Code 并计算其落库哈希，Identity 与 Operator 共用。
 *
 * <p>每个码 16 位、取自 32 个不易混淆的字符（去掉 0、1、I、O），共 80 位熵：熵足够高，库表泄露后无法离线穷举，
 * 因此用不加盐的 SHA-256 即可，校验时也能按哈希直接查找，不必像密码那样逐个比对慢哈希。
 */
public final class RecoveryCodes {
    /** 每次绑定或重新生成发放的数量。 */
    public static final int COUNT = 10;

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int GROUPS = 4;
    private static final int GROUP_LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    /** 生成一组互不相同的码，按 {@code XXXX-XXXX-XXXX-XXXX} 分组以便抄写。 */
    public static List<String> generate() {
        var codes = new ArrayList<String>(COUNT);
        while (codes.size() < COUNT) {
            var code = new StringBuilder();
            for (int group = 0; group < GROUPS; group++) {
                if (group > 0) code.append('-');
                for (int i = 0; i < GROUP_LENGTH; i++) code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
            }
            if (!codes.contains(code.toString())) codes.add(code.toString());
        }
        return codes;
    }

    /** 落库与查找用的哈希；忽略大小写、空格与连字符，用户怎么抄写都能匹配。 */
    public static String hash(String code) {
        String normalized = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
