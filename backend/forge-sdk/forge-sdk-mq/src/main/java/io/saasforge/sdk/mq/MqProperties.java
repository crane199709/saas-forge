package io.saasforge.sdk.mq;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 消息配置，前缀 {@code forge.mq}。
 *
 * @param enabled 是否启动投递器与消费端；关闭时发布事件仍写入 Outbox，待开启后补发
 * @param endpoints RocketMQ proxy 的 gRPC 地址，如 {@code 127.0.0.1:8081}
 * @param ssl 是否以 TLS 连接 proxy；客户端默认开启，而本地 compose 与测试容器中的 proxy 只提供明文端口
 * @param consumerGroup 消费组，未配置时取 {@code spring.application.name}
 * @param requestTimeout 单次发送的超时；MQ 不可用时投递器每轮最多阻塞这么久
 * @param pollInterval 投递器扫描到期事件的间隔，兜底事务提交后的即时唤醒
 * @param batchSize 每次扫描最多取出的事件数
 * @param initialBackoff 首次发送失败后的重试间隔，之后每次翻倍
 * @param maxBackoff 重试间隔上限
 */
@ConfigurationProperties("forge.mq")
public record MqProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("127.0.0.1:8081") String endpoints,
        @DefaultValue("false") boolean ssl,
        String consumerGroup,
        @DefaultValue("3s") Duration requestTimeout,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff) {

    /**
     * 第 {@code attempts + 1} 次发送失败后的重试间隔：从 {@link #initialBackoff} 起指数增长，不超过 {@link #maxBackoff}。
     *
     * @param attempts 本次之前已失败的次数
     * @return 距下一次重试的间隔
     */
    public Duration backoff(int attempts) {
        // 2^20 倍已远超任何合理上限，限制指数以免溢出
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempts, 20));
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
