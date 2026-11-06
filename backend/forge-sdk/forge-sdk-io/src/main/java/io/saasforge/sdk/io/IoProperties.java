package io.saasforge.sdk.io;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** IO 资源上限与保留期，线程数与队列共同限定单实例并发。 */
@ConfigurationProperties("forge.io")
public record IoProperties(
        @DefaultValue("2") int workers,
        @DefaultValue("8") int queueCapacity,
        @DefaultValue("10000") int importLimit,
        @DefaultValue("100000") int exportLimit,
        @DefaultValue("10s") Duration heartbeatInterval,
        @DefaultValue("2m") Duration heartbeatTimeout,
        @DefaultValue("7d") Duration retention) {
    public IoProperties {
        if (workers < 1
                || queueCapacity < 0
                || importLimit < 1
                || exportLimit < 1
                || exportLimit > 1048575
                || heartbeatInterval.isNegative()
                || heartbeatInterval.isZero()
                || heartbeatTimeout.compareTo(heartbeatInterval) <= 0
                || retention.isNegative()
                || retention.isZero()) throw new IllegalArgumentException("Invalid forge.io limits or durations");
    }
}
