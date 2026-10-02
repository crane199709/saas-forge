package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditConsumerIsolation;
import io.saas.forge.audit.application.AuditConsumerIsolationService;
import io.saas.forge.audit.application.AuditConsumerFailurePolicy;
import io.saas.forge.audit.application.AuditProcessingFailure;
import io.saas.forge.audit.application.AuditRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 将 Consumer 失败收窄为脱敏轨迹，只有重新通过完整校验的 Envelope 才能保存安全快照。 */
@Component
public class AuditConsumerFailureHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditConsumerFailureHandler.class);
    private static final Set<String> KNOWN_SOURCES = Set.of(
            SessionStartedEventValidator.SOURCE, TenantCreatedEventValidator.SOURCE, ExampleFactEventValidator.SOURCE);
    private static final Set<String> KNOWN_TYPES = Set.of(
            SessionStartedEventValidator.EVENT_TYPE,
            TenantContextSwitchedEventValidator.EVENT_TYPE,
            TenantCreatedEventValidator.EVENT_TYPE);

    private final ObjectMapper objectMapper;
    private final IamSessionEventValidator iamValidator;
    private final TenantAccessEventValidator tenantValidator;
    private final ExampleFactEventValidator exampleValidator;
    private final AuditConsumerTopology topology;
    private final AuditConsumerIsolationService service;
    private final AuditConsumerFailurePolicy policy;

    public AuditConsumerFailureHandler(
            ObjectMapper objectMapper,
            IamSessionEventValidator iamValidator,
            TenantAccessEventValidator tenantValidator,
            ExampleFactEventValidator exampleValidator,
            AuditConsumerTopology topology,
            AuditConsumerIsolationService service,
            AuditConsumerFailurePolicy policy) {
        this.objectMapper = objectMapper;
        this.iamValidator = iamValidator;
        this.tenantValidator = tenantValidator;
        this.exampleValidator = exampleValidator;
        this.topology = topology;
        this.service = service;
        this.policy = policy;
    }

    public void recordFailure(ConsumerRecord<?, ?> message, Exception exception, int attemptCount) {
        Locator locator = locate(message.value());
        service.recordProcessingFailure(new AuditProcessingFailure(
                topology.consumerName(message.topic()), message.topic(), message.partition(), message.offset(),
                safeFailureKey(message), locator.eventId(), locator.source(), locator.sourceType(),
                sha256(stringValue(message.value())),
                permanent(exception) ? "PERMANENT_VALIDATION" : "TRANSIENT_PROCESSING",
                diagnostic(exception), attemptCount));
    }

    public void recordFailureWithoutInterruptingRetry(
            ConsumerRecord<?, ?> message, Exception exception, int attemptCount) {
        try {
            recordFailure(message, exception, attemptCount);
        } catch (RuntimeException persistenceFailure) {
            // PostgreSQL 本身可能是瞬时故障来源；轨迹写入失败不能中断 Kafka 的既定重试周期。
            LOGGER.warn("Audit Consumer 第 {} 次失败轨迹暂不可写入: {}",
                    attemptCount, diagnostic(persistenceFailure));
        }
    }

    /** 返回前隔离事务已经提交，Kafka ErrorHandler 随后才可提交原消息 Offset。 */
    public void isolate(ConsumerRecord<?, ?> message, Exception exception) {
        boolean permanent = permanent(exception);
        String consumerName = topology.consumerName(message.topic());
        Locator locator = locate(message.value());
        String safeSnapshot = null;
        String isolationTopic = null;
        if (!permanent) {
            AuditRecord record = validateSafe(message, consumerName).orElseThrow(
                    () -> new IllegalStateException("ignored 事件不应进入 Audit 隔离恢复路径"));
            locator = new Locator(record.sourceEventId(), record.source(), record.sourceType());
            safeSnapshot = stringValue(message.value());
            isolationTopic = topology.isolationTopic(message.topic());
        }
        service.isolate(new AuditConsumerIsolation(
                consumerName, message.topic(), message.partition(), message.offset(),
                permanent ? null : stringValue(message.key()),
                locator.eventId(), locator.source(), locator.sourceType(),
                sha256(stringValue(message.value())),
                permanent ? "PERMANENT_VALIDATION" : "RETRY_EXHAUSTED",
                diagnostic(exception), permanent ? 1 : policy.maximumAttempts(),
                safeSnapshot, isolationTopic));
    }

    private Optional<AuditRecord> validateSafe(ConsumerRecord<?, ?> message, String consumerName) {
        String payload = stringValue(message.value());
        String orderingKey = stringValue(message.key());
        if (SessionStartedEventValidator.CONSUMER_NAME.equals(consumerName)) {
            return Optional.of(iamValidator.validate(message.topic(), orderingKey, consumerName, payload));
        }
        if (ExampleFactEventValidator.CONSUMER_NAME.equals(consumerName))
            return Optional.of(exampleValidator.validate(message.topic(), orderingKey, consumerName, payload));
        return tenantValidator.validate(message.topic(), orderingKey, consumerName, payload);
    }

    private Locator locate(Object value) {
        try {
            JsonNode envelope = objectMapper.readTree(stringValue(value));
            if (envelope == null || !envelope.isObject()) {
                return Locator.EMPTY;
            }
            return new Locator(
                    uuid(envelope.get("id")), knownText(envelope.get("source"), KNOWN_SOURCES),
                    knownText(envelope.get("type"), java.util.stream.Stream.concat(KNOWN_TYPES.stream(), ExampleFactEventValidator.TYPES.stream()).collect(java.util.stream.Collectors.toSet())));
        } catch (RuntimeException exception) {
            return Locator.EMPTY;
        }
    }

    private static UUID uuid(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String knownText(JsonNode node, Set<String> allowed) {
        return node != null && node.isTextual() && allowed.contains(node.asText()) ? node.asText() : null;
    }

    private static boolean permanent(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof InvalidAuditEventException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String diagnostic(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private static String sha256(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private String safeFailureKey(ConsumerRecord<?, ?> message) {
        String key = stringValue(message.key());
        if (ExampleFactEventValidator.CONSUMER_NAME.equals(topology.consumerName(message.topic()))
                && !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) return null;
        return key;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private record Locator(UUID eventId, String source, String sourceType) {
        private static final Locator EMPTY = new Locator(null, null, null);
    }
}
