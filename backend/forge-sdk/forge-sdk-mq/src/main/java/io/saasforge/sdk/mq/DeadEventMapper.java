package io.saasforge.sdk.mq;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/** Dead Event 是服务级基础设施，跨租户保存捕获结果，不启用租户拦截与 RLS。 */
@InterceptorIgnore(tenantLine = "true")
interface DeadEventMapper {
    /**
     * 按消费组与事件 ID 幂等捕获死信，保留原始字节以便解析失败后仍可排查与重放。
     *
     * @return 新建时为 1，已有记录时为 0
     */
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("consumerGroup") String consumerGroup,
            @Param("eventId") String eventId,
            @Param("tenantId") UUID tenantId,
            @Param("originalTenantId") String originalTenantId,
            @Param("topic") String topic,
            @Param("tag") String tag,
            @Param("payload") byte[] payload,
            @Param("deliveryAttempt") int deliveryAttempt,
            @Param("lastError") String lastError);
}
