package io.saasforge.sdk.mq;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在业务事务中发布事件（ADR-0008）。
 *
 * <p>事件写入 Outbox 表，与业务数据同一本地事务提交或回滚；事务提交后由 SDK 投递器发送到 RocketMQ，
 * 失败按退避重试。消息携带发布时的租户与事件 ID，消费端据此恢复租户上下文并去重。
 */
public final class EventPublisher {
    /** 消息属性：事件 ID，即消费端去重用的消息 ID，Outbox 重发时保持不变。 */
    public static final String EVENT_ID_PROPERTY = "forgeEventId";

    /** 消息属性：发布时的租户 ID，无租户上下文发布时不设置。 */
    public static final String TENANT_ID_PROPERTY = "forgeTenantId";

    private final OutboxMapper mapper;
    private final JsonMapper json;
    private final Runnable afterCommit;

    EventPublisher(OutboxMapper mapper, JsonMapper json, Runnable afterCommit) {
        this.mapper = mapper;
        this.json = json;
        this.afterCommit = afterCommit;
    }

    /**
     * 在当前事务中发布事件，事务回滚则事件不存在。
     *
     * <p>Tag 取事件类型的简单类名（如 {@code OrderCreated}），订阅方 {@link EventHandler#eventType()} 须是同名类型。
     * 消息体按 JSON 序列化，事件须是接口模型而不是数据库实体。
     *
     * @param topic 目标 Topic
     * @param event 事件消息体
     * @throws IllegalStateException 不在事务中调用时；脱离事务写入的事件无法与业务数据保持一致
     */
    public void publish(String topic, Object event) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(event, "event");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Events must be published inside a transaction so that they commit "
                    + "or roll back together with business data");
        }
        UUID tenantId = CurrentTenant.find().orElse(null);
        mapper.insert(
                UuidV7.generate(), tenantId, topic, event.getClass().getSimpleName(), json.writeValueAsString(event));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                afterCommit.run();
            }
        });
    }
}
