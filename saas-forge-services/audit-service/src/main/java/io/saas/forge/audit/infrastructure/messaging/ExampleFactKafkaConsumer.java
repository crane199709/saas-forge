package io.saas.forge.audit.infrastructure.messaging;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.saas.forge.audit.application.AuditRecordService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "saas.forge.audit.example-consumer.enabled", havingValue = "true")
public class ExampleFactKafkaConsumer {
    public static final String LISTENER_ID = "audit-example-events";
    private final ExampleFactEventValidator validator;
    private final AuditRecordService service;
    private final OpenTelemetry telemetry;
    private static final TextMapGetter<Headers> GETTER = new TextMapGetter<>() {
        public Iterable<String> keys(Headers carrier) {
            var keys = new ArrayList<String>(); carrier.forEach(header -> keys.add(header.key())); return keys;
        }
        public String get(Headers carrier, String key) {
            var iterator = carrier.headers(key).iterator();
            if (!iterator.hasNext()) return null;
            var header = iterator.next();
            return iterator.hasNext() || header.value() == null ? null : new String(header.value(), StandardCharsets.US_ASCII);
        }
    };

    public ExampleFactKafkaConsumer(ExampleFactEventValidator validator, AuditRecordService service, OpenTelemetry telemetry) {
        this.validator = validator; this.service = service; this.telemetry = telemetry;
    }

    @KafkaListener(id = LISTENER_ID, topics = "${saas.forge.audit.example-topic}",
            groupId = ExampleFactEventValidator.CONSUMER_NAME)
    public void consume(ConsumerRecord<String, String> message, Acknowledgment acknowledgment) {
        Context parent = W3CTraceContextPropagator.getInstance().extract(Context.root(), message.headers(), GETTER);
        var span = telemetry.getTracer("saas-forge.audit.example").spanBuilder("example.fact.consume")
                .setParent(parent).setSpanKind(SpanKind.CONSUMER).startSpan();
        try (Scope scope = parent.with(span).makeCurrent()) {
            var record = validator.validate(message.topic(), message.key(), ExampleFactEventValidator.CONSUMER_NAME, message.value());
            boolean inserted = service.record(ExampleFactEventValidator.CONSUMER_NAME, record);
            io.saas.forge.observability.ForgeLogs.fact(span, inserted ? "audit.record.appended" : "audit.record.duplicate");
            acknowledgment.acknowledge();
        } catch (RuntimeException failure) {
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally { span.end(); }
    }
}
