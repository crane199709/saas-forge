package io.saasforge.sdk.referencedata;

import io.saasforge.sdk.provisioning.ReferenceDataChanged;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 每个实例以自己独占的消费组订阅 {@link ReferenceDataChanged}，收到后清除该租户的缓存。
 *
 * <p>缓存在每个实例的进程内，事件必须送达服务的每个实例。服务的 {@code EventHandler} 共用一个消费组（集群消费），一条消息只分给其中一个实例；
 * 当前使用的 gRPC 客户端（rocketmq-client-java 5.x）不支持广播消费，因此每个实例启动时生成一个唯一的消费组，各组都收到全部消息，效果即广播。
 * 清除缓存是幂等的，不经 SDK 按消费组登记的消费记录去重，也不开启事务。
 *
 * <p>代价：实例退出后其消费组仍留在 Broker 上（无订阅者，不影响投递），需要运维按前缀定期清理；Broker 须允许自动创建订阅组
 * （{@code autoCreateSubscriptionGroup}，默认开启）。漏收的事件由缓存过期兜底。
 */
final class ReferenceDataChangedListener implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ReferenceDataChangedListener.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ClientConfiguration configuration;
    private final String consumerGroup;
    private final ReferenceData referenceData;
    private volatile PushConsumer consumer;

    ReferenceDataChangedListener(ClientConfiguration configuration, ReferenceData referenceData) {
        this.configuration = configuration;
        this.consumerGroup = referenceData.service() + "-reference-data-" + UUID.randomUUID();
        this.referenceData = referenceData;
    }

    @Override
    public void start() {
        try {
            consumer = ClientServiceProvider.loadService()
                    .newPushConsumerBuilder()
                    .setClientConfiguration(configuration)
                    .setConsumerGroup(consumerGroup)
                    .setSubscriptionExpressions(Map.of(
                            ReferenceDataChanged.TOPIC,
                            new FilterExpression(ReferenceDataChanged.class.getSimpleName(), FilterExpressionType.TAG)))
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

    private ConsumeResult consume(MessageView message) {
        ByteBuffer body = message.getBody();
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        try {
            referenceData.evict(
                    JSON.readValue(bytes, ReferenceDataChanged.class).tenantId());
        } catch (RuntimeException e) {
            // 消息体无法解析时重投也无济于事，缓存过期兜底
            log.warn("Unreadable ReferenceDataChanged message {} skipped", message.getMessageId(), e);
        }
        return ConsumeResult.SUCCESS;
    }
}
