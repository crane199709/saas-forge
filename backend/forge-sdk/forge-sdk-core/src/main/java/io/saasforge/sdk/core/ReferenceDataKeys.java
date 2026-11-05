package io.saasforge.sdk.core;

import java.util.UUID;

/**
 * iam 在 Redis 发布的参考数据版本号的键约定，做法同 Membership version（ADR-0005）：iam 写入，业务服务只读。
 *
 * <p>键是全局键，按 {@code iam:{tenant_id}:} 归属租户，不使用业务服务的租户前缀模板。
 */
public final class ReferenceDataKeys {
    private ReferenceDataKeys() {}

    /**
     * 租户参考数据的版本号，十进制字符串。iam 在租户修改字典项或参数的事务提交后递增；业务服务读取前比对，与缓存时不同即重新拉取。
     * 键缺失表示尚未修改过或已被淘汰，读取方只能依靠缓存过期。
     *
     * @param tenantId 租户标识
     * @return Redis 键
     */
    public static String version(UUID tenantId) {
        return "iam:" + tenantId + ":reference-data:version";
    }
}
