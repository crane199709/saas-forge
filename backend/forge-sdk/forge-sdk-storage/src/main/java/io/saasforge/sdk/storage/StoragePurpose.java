package io.saasforge.sdk.storage;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Business Service 以 Java 常量声明的 Storage Purpose，不随环境或租户变化。
 *
 * @param code 用途编码，以小写字母开头，只含小写字母、数字和分隔单词的短横线，最长 64 个字符；unspecified 为存量迁移保留
 * @param extensions 文件扩展名白名单，不含点，大小写不敏感；不能为空
 * @param maxSize 单个文件的大小上限（字节），大于零且不超过 {@link #MAX_OBJECT_SIZE}
 * @param downloadUrlTtl 下载 URL 时长，秒级且介于 1 秒和 7 天之间（S3 签名上限）
 * @param retentionDays 服务声明的保留天数，大于零；null 表示永久保留
 */
public record StoragePurpose(
        String code, Set<String> extensions, long maxSize, Duration downloadUrlTtl, Integer retentionDays) {
    /** 未声明保留期的用途永久保留，兼容既有服务声明。 */
    public StoragePurpose(String code, Set<String> extensions, long maxSize, Duration downloadUrlTtl) {
        this(code, extensions, maxSize, downloadUrlTtl, null);
    }

    /** SDK 的单次 S3 PUT 全局大小上限：5 GiB。 */
    public static final long MAX_OBJECT_SIZE = 5L * 1024 * 1024 * 1024;

    /** 校验声明，常量声明非法时立即失败，避免服务带错误规则启动。 */
    public StoragePurpose {
        if (code == null
                || code.length() > 64
                || !code.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
                || code.equals("unspecified")) {
            throw new IllegalArgumentException(
                    "Storage purpose code must be lowercase kebab-case, at most 64 characters and not unspecified");
        }
        if (extensions == null
                || extensions.isEmpty()
                || extensions.stream().anyMatch(value -> value == null || !value.matches("[a-zA-Z0-9]+"))) {
            throw new IllegalArgumentException(
                    "Storage purpose extensions must be a nonempty set of extensions without dots");
        }
        if (maxSize <= 0 || maxSize > MAX_OBJECT_SIZE) {
            throw new IllegalArgumentException(
                    "Storage purpose maxSize must be between 1 and " + MAX_OBJECT_SIZE + " bytes");
        }
        if (downloadUrlTtl == null
                || downloadUrlTtl.isZero()
                || downloadUrlTtl.isNegative()
                || downloadUrlTtl.compareTo(Duration.ofDays(7)) > 0
                || downloadUrlTtl.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Storage purpose downloadUrlTtl must be whole seconds between 1 second and 7 days");
        }
        if (retentionDays != null && retentionDays <= 0) {
            throw new IllegalArgumentException("Storage purpose retentionDays must be greater than zero");
        }
        extensions = extensions.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
