package io.saasforge.sdk.numbering;

/**
 * Numbering Rule：业务服务在本地声明的 Business Number 格式与计数方式，不上报 iam，Tenant 不能修改。通常声明为常量：
 *
 * <pre>{@code
 * static final NumberingRule ORDER = new NumberingRule("order", "SO", DateSegment.DAY, 4); // SO-20261104-0007
 * }</pre>
 *
 * <p>编号由前缀、日期段（可选）与序号三段以 {@code -} 连接；序号不足 {@code minDigits} 位时补零，超出时位数继续增长。
 * 计数周期跟随日期段，无日期段时永不重置。
 *
 * @param code 规则编码，在服务内唯一，是计数行的键；改名等于换一套计数，已发布后不要修改
 * @param prefix 固定前缀，如 {@code SO}
 * @param dateSegment 日期段与计数周期；null 表示没有日期段，永不重置
 * @param minDigits 序号最小位数，1–18
 */
public record NumberingRule(String code, String prefix, DateSegment dateSegment, int minDigits) {
    // 与计数表 rule 列的宽度一致
    private static final int MAX_CODE_LENGTH = 64;
    // 计数为 bigint，最多 19 位；留出余量让「超出位数继续增长」始终可能
    private static final int MAX_DIGITS = 18;
    // 无日期段时的计数周期：周期列不为 null，部分唯一索引才能约束「租户 + 规则 + 周期」一行
    static final String NO_PERIOD = "";

    /**
     * 校验规则声明，规则通常是常量，声明错误在类加载时即失败。
     *
     * @throws IllegalArgumentException 编码为空或超过 64 个字符、前缀为空，或 {@code minDigits} 不在 1–18 之间
     */
    public NumberingRule {
        if (code == null || code.isBlank() || code.length() > MAX_CODE_LENGTH) {
            throw new IllegalArgumentException("Numbering rule code must be 1-" + MAX_CODE_LENGTH + " characters");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("Numbering rule " + code + " must have a prefix");
        }
        if (minDigits < 1 || minDigits > MAX_DIGITS) {
            throw new IllegalArgumentException("Numbering rule " + code + " minDigits must be 1-" + MAX_DIGITS);
        }
    }

    String format(String period, long sequence) {
        String digits = String.format("%0" + minDigits + "d", sequence);
        return dateSegment == null ? prefix + "-" + digits : prefix + "-" + period + "-" + digits;
    }
}
