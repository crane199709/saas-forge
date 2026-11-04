package io.saasforge.sdk.provisioning;

import java.util.UUID;

/**
 * 租户修改了参考数据（Extensible Dictionary 的字典项或 Parameter 的覆盖值）：iam 在同一事务中经 Outbox 发布（Topic {@link #TOPIC}），
 * 携带该租户的租户上下文。
 *
 * <p>消息只说明哪个租户变了，不带变更内容：订阅方清除该租户的参考数据缓存，下次读取时从 iam 重新拉取合并后的生效值。
 * 消息可能重复送达，清除缓存是幂等的。
 *
 * @param tenantId 修改了参考数据的租户 ID
 */
public record ReferenceDataChanged(UUID tenantId) {
    /** 参考数据变更事件所在的 Topic。 */
    public static final String TOPIC = "forge-reference-data";
}
