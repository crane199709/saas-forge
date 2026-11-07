package io.saasforge.sdk.mq;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.Instant;
import java.util.List;
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
    /** 按捕获时间倒序读取元数据，不受 Data Policy 过滤。 */
    List<DeadEventResponse> list(@Param("status") String status, @Param("offset") long offset, @Param("size") int size);

    /** 统计指定状态的记录，不受 Data Policy 过滤。 */
    long count(@Param("status") String status);
    /** 按 ID 读取元数据，不受 Data Policy 过滤。 */
    DeadEventResponse find(@Param("id") UUID id);

    /** 只供服务内重放读取原始消息，不受 Data Policy 过滤。 */
    DeadEventEntry replayInput(@Param("id") UUID id);

    /** 仅将待处置记录转入终态；与 Handler 同事务时行锁会保护全部业务效果。不受 Data Policy 过滤。 */
    int resolve(@Param("id") UUID id, @Param("status") String status, @Param("reason") String reason);
    /** 失败回滚后刷新仍待处置的错误，不改变终态，不受 Data Policy 过滤。 */
    int updateError(@Param("id") UUID id, @Param("error") String error);
    /** 只删除处置时间早于保留期的终态记录，不受 Data Policy 过滤。 */
    int deleteResolvedBefore(@Param("cutoff") Instant cutoff);
    /** 租户删除时清理其全部基础设施记录；显式限定 tenant_id，不受 Data Policy 过滤。 */
    int deleteTenant(@Param("tenantId") UUID tenantId);
}
