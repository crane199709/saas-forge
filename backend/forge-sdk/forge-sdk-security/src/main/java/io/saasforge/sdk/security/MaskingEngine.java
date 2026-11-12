package io.saasforge.sdk.security;

import io.saasforge.sdk.core.api.ForgeException;
import java.util.Set;

/**
 * 固定策略的纯脱敏引擎，供所有执行通道共用；不接受表达式、正则或外部策略注册。
 *
 * <p>保留长度按 Unicode 码点计算。各策略的固定口径与示例如下：
 * <ul>
 *   <li>{@code mask_middle} 保留首尾各一位：{@code abcdef → a****f}。
 *   <li>{@code mask_tail4} 保留末四位：{@code 1234567890 → ******7890}。
 *   <li>{@code keepFirst} 使用非负 {@code keepFirst} 参数：{@code abcdef, n=2 → ab****}；
 *       {@code n=0} 全部替换；{@code n} 覆盖整值时因未改变而失败。
 *   <li>{@code replace} 使用非空白 {@code replacement}：{@code secret → [hidden]}。
 *   <li>{@code email} 保留本地部分首位和域名：{@code alice@example.com → a****@example.com}；
 *       单字本地部分全部替换，缺少本地部分或域名、包含多个 {@code @} 时整值替换。
 *   <li>{@code phone} 保留前三位与末四位：{@code 13812345678 → 138****5678}。
 *   <li>{@code idcard} 保留前六位与末四位：{@code 110101199001011234 → 110101********1234}。
 *   <li>{@code bankcard} 保留前四位与末四位：{@code 6222021234567890 → 6222********7890}。
 *   <li>{@code name} 保留首位：{@code 张小明 → 张**}。
 * </ul>
 *
 * <p>固定保留范围覆盖整个非空短值时全部替换为等码点数的星号，{@code keepFirst} 除外。
 * 手机号、证件和银行卡策略只决定展示保留范围，不验证业务格式。所有策略均拒绝未改变的非空结果。
 */
public final class MaskingEngine {
    private static final Set<String> STRATEGIES =
            Set.of("mask_middle", "mask_tail4", "keepFirst", "replace", "email", "phone", "idcard", "bankcard", "name");

    /** 创建不依赖外部配置的引擎。 */
    public MaskingEngine() {}

    /**
     * 校验固定策略名及其参数；保存或启动时可独立调用。
     *
     * <p>{@code keepFirst} 只接受非负整数参数，{@code replace} 只接受非空白替换文本，
     * 其他策略不接受任何参数；所有不属于当前策略的参数必须为 null。
     *
     * @param rule 待校验规则
     * @throws ForgeException 策略不合法；异常不包含规则参数
     */
    public void validate(MaskingRule rule) {
        if (rule == null || rule.strategy() == null || rule.strategy().isBlank()) {
            throw new ForgeException(MaskingErrorCode.INVALID_RULE);
        }
        if (!STRATEGIES.contains(rule.strategy())) {
            throw new ForgeException(MaskingErrorCode.UNKNOWN_STRATEGY);
        }
        boolean valid = switch (rule.strategy()) {
            case "keepFirst" -> rule.keepFirst() != null && rule.keepFirst() >= 0 && rule.replacement() == null;
            case "replace" ->
                rule.keepFirst() == null
                        && rule.replacement() != null
                        && !rule.replacement().isBlank();
            default -> rule.keepFirst() == null && rule.replacement() == null;
        };
        if (!valid) {
            throw new ForgeException(MaskingErrorCode.INVALID_RULE);
        }
    }

    /**
     * 先校验规则再执行；合法规则下 null 和空串保持原样。
     *
     * <p>异常消息、参数和原因均不带原文、策略名或规则参数。
     *
     * @param rule 脱敏规则
     * @param value 待脱敏值
     * @return 脱敏结果
     * @throws ForgeException 规则非法或非空结果未改变；调用方不得回退为原文
     */
    public String apply(MaskingRule rule, String value) {
        validate(rule);
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = switch (rule.strategy()) {
            case "mask_middle" -> mask(value, 1, 1);
            case "mask_tail4" -> mask(value, 0, 4);
            case "keepFirst" -> keepFirst(value, rule.keepFirst());
            case "replace" -> rule.replacement();
            case "email" -> email(value);
            case "phone" -> mask(value, 3, 4);
            case "idcard" -> mask(value, 6, 4);
            case "bankcard" -> mask(value, 4, 4);
            case "name" -> mask(value, 1, 0);
            default -> throw new ForgeException(MaskingErrorCode.UNKNOWN_STRATEGY);
        };
        if (value.equals(result)) {
            throw new ForgeException(MaskingErrorCode.UNCHANGED);
        }
        return result;
    }

    /**
     * 使用与执行通道相同的校验和算法预览样例；失败时不返回样例或未生效结果。
     *
     * @param rule 待预览规则
     * @param value 样例值
     * @return 成功的脱敏结果，或安全的固定失败原因
     */
    public MaskingPreview preview(MaskingRule rule, String value) {
        try {
            return new MaskingPreview(true, apply(rule, value), null, null);
        } catch (ForgeException error) {
            String reason = switch ((MaskingErrorCode) error.errorCode()) {
                case INVALID_RULE -> "Invalid masking rule parameters";
                case UNKNOWN_STRATEGY -> "Unknown masking strategy";
                case UNCHANGED -> "Masking did not change the value";
            };
            return new MaskingPreview(false, null, error.errorCode().code(), reason);
        }
    }

    private String mask(String value, int prefix, int suffix) {
        int length = value.codePointCount(0, value.length());
        if (length <= prefix + suffix) {
            return "*".repeat(length);
        }
        return value.substring(0, value.offsetByCodePoints(0, prefix))
                + "*".repeat(length - prefix - suffix)
                + value.substring(value.offsetByCodePoints(0, length - suffix));
    }

    private String keepFirst(String value, int count) {
        int length = value.codePointCount(0, value.length());
        int keep = Math.min(count, length);
        return value.substring(0, value.offsetByCodePoints(0, keep)) + "*".repeat(length - keep);
    }

    private String email(String value) {
        int at = value.indexOf('@');
        if (at < 1 || at == value.length() - 1 || at != value.lastIndexOf('@')) {
            return "*".repeat(value.codePointCount(0, value.length()));
        }
        return mask(value.substring(0, at), 1, 0) + value.substring(at);
    }
}
