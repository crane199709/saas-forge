package io.saasforge.sdk.mq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 以服务的消费组订阅全部 {@link EventHandler}，按 Topic 与 Tag 分派，恢复租户上下文并按消息 ID 去重。
 *
 * <p>同一消费组的所有实例订阅关系必须一致，因此每个服务只建一个 PushConsumer。启动时拉取 Topic 路由，
 * MQ 不可用或 Topic 不存在时启动失败，而不是静默地不消费。
 */
final class EventConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final ClientConfiguration configuration;
    private final String consumerGroup;
    private final Map<String, EventHandler<?>> handlers = new HashMap<>();
    private final ConsumedMessageMapper consumed;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final DeadEventMapper deadEvents;
    private final TransactionTemplate deadTransaction;
    private final int maxDeliveryAttempts;
    private final Counter deadEventsCounter;
    private volatile PushConsumer consumer;

    EventConsumer(
            ClientConfiguration configuration,
            String consumerGroup,
            List<EventHandler<?>> handlers,
            ConsumedMessageMapper consumed,
            TransactionTemplate transaction,
            JsonMapper json,
            DeadEventMapper deadEvents,
            int maxDeliveryAttempts,
            MeterRegistry metrics) {
        this.configuration = configuration;
        this.consumerGroup = consumerGroup;
        for (EventHandler<?> handler : handlers) {
            String key = key(handler.topic(), handler.eventType().getSimpleName());
            if (this.handlers.putIfAbsent(key, handler) != null) {
                throw new IllegalStateException("Duplicate EventHandler for " + key);
            }
        }
        this.consumed = consumed;
        this.transaction = transaction;
        this.json = json;
        this.deadEvents = deadEvents;
        this.maxDeliveryAttempts = maxDeliveryAttempts;
        this.deadEventsCounter = metrics.counter("forge.mq.dead.events", "consumer.group", consumerGroup);
        this.deadTransaction = new TransactionTemplate(transaction.getTransactionManager());
        this.deadTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean isAutoStartup() {
        return configuration != null;
    }

    @Override
    public void start() {
        Map<String, FilterExpression> subscriptions = handlers.values().stream()
                .collect(Collectors.groupingBy(
                        EventHandler::topic,
                        Collectors.mapping(
                                handler -> handler.eventType().getSimpleName(),
                                Collectors.collectingAndThen(
                                        Collectors.joining(" || "),
                                        tags -> new FilterExpression(tags, FilterExpressionType.TAG)))));
        try {
            consumer = ClientServiceProvider.loadService()
                    .newPushConsumerBuilder()
                    .setClientConfiguration(configuration)
                    .setConsumerGroup(consumerGroup)
                    .setSubscriptionExpressions(subscriptions)
                    .setMessageListener(this::consume)
                    .build();
        } catch (ClientException e) {
            throw new IllegalStateException("Failed to start RocketMQ consumer group " + consumerGroup, e);
        }
    }

    @Override
    public void stop() {
        PushConsumer current = consumer;
        consumer = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (IOException e) {
            log.warn("Failed to close RocketMQ consumer group {}", consumerGroup, e);
        }
    }

    @Override
    public boolean isRunning() {
        return consumer != null;
    }

    ConsumeResult consume(MessageView message) {
        String tag = message.getTag().orElse("");
        EventHandler<?> handler = handlers.get(key(message.getTopic(), tag));
        if (handler == null) {
            // 订阅表达式已按 Tag 过滤，到这里说明订阅关系与处理器不一致，重投也无法处理
            log.warn(
                    "No EventHandler for topic {} tag {}, message {} skipped",
                    message.getTopic(),
                    tag,
                    message.getMessageId());
            return ConsumeResult.SUCCESS;
        }
        Map<String, String> properties = message.getProperties();
        // 非 SDK 发布的消息没有事件 ID，退回 RocketMQ 消息 ID，此时同一业务事件多次发送无法识别为重复
        String messageId = properties.getOrDefault(
                EventPublisher.EVENT_ID_PROPERTY, message.getMessageId().toString());
        String tenantId = properties.get(EventPublisher.TENANT_ID_PROPERTY);
        ByteBuffer body = message.getBody().duplicate();
        byte[] payload = new byte[body.remaining()];
        body.get(payload);
        try {
            handle(consumerGroup, messageId, tenantId, message.getTopic(), tag, payload);
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException e) {
            log.error("Failed to handle message {} of topic {} tag {}", messageId, message.getTopic(), tag, e);
            if (message.getDeliveryAttempt() < maxDeliveryAttempts) {
                return ConsumeResult.FAILURE;
            }
            try {
                // Handler 的事务已退出并回滚；即使调用方还有事务，死信也必须独立提交后才能确认 MQ 消息。
                Integer inserted = deadTransaction.execute(status -> deadEvents.insertIfAbsent(
                        UuidV7.generate(),
                        consumerGroup,
                        messageId,
                        tenant(tenantId),
                        tenantId,
                        message.getTopic(),
                        tag,
                        payload,
                        message.getDeliveryAttempt(),
                        DeadEventErrorSummary.of(e)));
                if (Integer.valueOf(1).equals(inserted)) {
                    deadEventsCounter.increment();
                }
                return ConsumeResult.SUCCESS;
            } catch (RuntimeException captureFailure) {
                log.error(
                        "Failed to persist dead event {} for consumer group {}",
                        messageId,
                        consumerGroup,
                        captureFailure);
                return ConsumeResult.FAILURE;
            }
        }
    }

    /** 原始消费与服务内重放共用去重、租户恢复和 Handler 事务；处理器被移除时重放必须失败。 */
    void handle(String group, String eventId, String tenantId, String topic, String tag, byte[] payload) {
        EventHandler<?> handler = handlers.get(key(topic, tag));
        if (handler == null) {
            throw new IllegalStateException("No EventHandler for topic " + topic + " tag " + tag);
        }
        Runnable work = () -> transaction.executeWithoutResult(status -> {
            if (consumed.insertIfAbsent(group, eventId) == 1) {
                dispatch(handler, payload);
            }
        });
        // 先绑定租户再开启事务：连接在事务开始时取得，RLS 会话变量随之设置。
        if (tenantId == null) {
            work.run();
        } else {
            CurrentTenant.run(UUID.fromString(tenantId), work);
        }
    }

    private <T> void dispatch(EventHandler<T> handler, byte[] payload) {
        handler.handle(json.readValue(payload, handler.eventType()));
    }

    private static UUID tenant(String tenantId) {
        try {
            return tenantId == null ? null : UUID.fromString(tenantId);
        } catch (IllegalArgumentException ignored) {
            // 非法租户头本身也会造成消费失败，不能因此再拒绝保存死信。
            return null;
        }
    }

    private static String key(String topic, String tag) {
        return topic + "#" + tag;
    }
}
