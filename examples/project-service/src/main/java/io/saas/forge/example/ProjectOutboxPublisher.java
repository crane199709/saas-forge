package io.saas.forge.example;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 发布已提交事实；独立租约令牌隔离旧发布者，确认丢失时以同一事件 ID 重投。 */
@Component
@ConditionalOnProperty(name = "saas.forge.example.outbox.enabled", havingValue = "true", matchIfMissing = true)
class ProjectOutboxPublisher {
    private final ProjectOutboxMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final OpenTelemetry telemetry;
    private final Duration lease;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };

    ProjectOutboxPublisher(ProjectOutboxMapper mapper, KafkaTemplate<String, String> kafka,
            PlatformTransactionManager manager, OpenTelemetry telemetry,
            @Value("${saas.forge.example.outbox.lease-duration:PT30S}") Duration lease) {
        if (lease.compareTo(Duration.ofSeconds(15)) < 0) throw new IllegalArgumentException("Outbox 租约不足以覆盖发送超时");
        this.mapper = mapper; this.kafka = kafka; this.transaction = new TransactionTemplate(manager);
        this.telemetry = telemetry; this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${saas.forge.example.outbox.publish-delay:PT1S}")
    public void publishNext() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var event = transaction.execute(status -> mapper.claim(new ProjectOutboxMapper.Claim(
                UUID.randomUUID().toString(), now, now.plus(lease))));
        if (event == null) return;
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(),
                event.traceparent() == null ? Map.of() : Map.of("traceparent", event.traceparent()), GETTER);
        var span = telemetry.getTracer("saas-forge.example.outbox").spanBuilder("example.fact.publish")
                .setParent(parent).setSpanKind(SpanKind.PRODUCER).startSpan();
        try (Scope scope = parent.with(span).makeCurrent()) {
            var message = new ProducerRecord<String, String>(event.topic(), event.orderingKey(), event.payload());
            W3CTraceContextPropagator.getInstance().inject(Context.current(), message.headers(),
                    (headers, key, value) -> headers.add(key, value.getBytes(StandardCharsets.US_ASCII)));
            try {
                kafka.send(message).get(10, TimeUnit.SECONDS);
                Integer confirmed = transaction.execute(status -> mapper.published(new ProjectOutboxMapper.Completion(
                        event.eventId(), event.claimToken(), OffsetDateTime.now(ZoneOffset.UTC), null)));
                if (!Integer.valueOf(1).equals(confirmed)) throw new IllegalStateException("Outbox 领取已被替换");
                io.saas.forge.observability.ForgeLogs.fact(span, "example.fact.published");
            } catch (Exception failure) {
                span.setStatus(StatusCode.ERROR);
                io.saas.forge.observability.ForgeLogs.fact(span, "example.fact.retry");
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                long delay = Math.min(60, 1L << Math.min(event.attemptCount(), 6));
                transaction.executeWithoutResult(status -> mapper.retry(new ProjectOutboxMapper.Completion(
                        event.eventId(), event.claimToken(), OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delay),
                        failure.getClass().getSimpleName())));
            }
        } finally { span.end(); }
    }
}
