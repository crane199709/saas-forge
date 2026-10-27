package io.saasforge.sdk.mq;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;

/**
 * 把 Outbox 事件发送到 RocketMQ。
 *
 * <p>Producer 在首次发送时才创建：MQ 不可用时服务照常启动，事件留在 Outbox 中，由投递器重试时再尝试连接。
 * 只由投递器的单个线程调用，不需要同步。
 */
final class RocketMqSender implements AutoCloseable {
    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final ClientConfiguration configuration;
    private Producer producer;

    RocketMqSender(ClientConfiguration configuration) {
        this.configuration = configuration;
    }

    void send(OutboxEntry entry) throws ClientException {
        var message = provider.newMessageBuilder()
                .setTopic(entry.topic())
                .setTag(entry.tag())
                .setKeys(entry.id().toString())
                .addProperty(EventPublisher.EVENT_ID_PROPERTY, entry.id().toString());
        if (entry.tenantId() != null) {
            message.addProperty(
                    EventPublisher.TENANT_ID_PROPERTY, entry.tenantId().toString());
        }
        producer()
                .send(message.setBody(entry.payload().getBytes(StandardCharsets.UTF_8))
                        .build());
    }

    private Producer producer() throws ClientException {
        if (producer == null) {
            // 重试由投递器按退避统一安排，客户端内部不再重试，避免一次发送阻塞过久
            producer = provider.newProducerBuilder()
                    .setClientConfiguration(configuration)
                    .setMaxAttempts(1)
                    .build();
        }
        return producer;
    }

    @Override
    public void close() throws IOException {
        if (producer != null) {
            producer.close();
        }
    }
}
