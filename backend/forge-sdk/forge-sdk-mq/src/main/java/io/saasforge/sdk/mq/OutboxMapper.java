package io.saasforge.sdk.mq;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * Outbox 表读写，SQL 在同目录的 {@code OutboxMapper.xml}。
 *
 * <p>Outbox 是服务级基础设施表：投递器没有租户上下文，需要跨租户扫描，因此不注入租户条件，表上也不启用 RLS。
 */
@InterceptorIgnore(tenantLine = "true")
interface OutboxMapper {
    /**
     * 写入一条立即到期的事件。不受 Data Policy 过滤。
     *
     * @param id 事件 ID
     * @param tenantId 发布时的租户，可为 null
     * @param topic 目标 Topic
     * @param tag 事件类型名
     * @param payload JSON 消息体
     */
    void insert(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("topic") String topic,
            @Param("tag") String tag,
            @Param("payload") String payload);

    /** 返回全部尚未发送的 Outbox 事件数，包括退避中的事件；不受租户或 Data Policy 过滤。 */
    long countPending();

    /**
     * 锁定并取出已到期、未发送的事件，跳过其他实例已锁定的行，多实例并发投递时互不重复。不受 Data Policy 过滤。
     *
     * @param limit 最多取出的条数
     * @return 按到期时间排序的事件
     */
    List<OutboxEntry> lockDue(@Param("limit") int limit);

    /**
     * 标记事件已发送。不受 Data Policy 过滤。
     *
     * @param id 事件 ID
     */
    void markSent(@Param("id") UUID id);

    /**
     * 记录一次发送失败，并把下次重试推迟到 {@code backoffMillis} 之后。不受 Data Policy 过滤。
     *
     * @param id 事件 ID
     * @param backoffMillis 距下次重试的毫秒数
     * @param error 失败原因，便于排障
     */
    void markFailed(@Param("id") UUID id, @Param("backoffMillis") long backoffMillis, @Param("error") String error);
}
