package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecordService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public final class IamPasswordKafkaConsumer {
    public static final String LISTENER_ID = "audit-iam-password-events";
    private final IamPasswordEventValidator validator;
    private final AuditRecordService service;
    private final ObjectMapper json;

    public IamPasswordKafkaConsumer(IamPasswordEventValidator validator, AuditRecordService service,
            ObjectMapper json) {
        this.validator = validator;
        this.service = service;
        this.json = json;
    }

    @KafkaListener(id = LISTENER_ID, topics = "${saas.forge.audit.iam-session-topic}",
            groupId = IamPasswordEventValidator.CONSUMER_NAME)
    public void consume(ConsumerRecord<String, String> message, Acknowledgment acknowledgment) {
        try {
            JsonNode event = json.readTree(message.value());
            String type = event.path("type").asText();
            if (SessionStartedEventValidator.EVENT_TYPE.equals(type)
                    || TenantContextSwitchedEventValidator.EVENT_TYPE.equals(type)
                    || PasswordRecoveredEventValidator.EVENT_TYPE.equals(type)) {
                acknowledgment.acknowledge();
                return;
            }
        } catch (RuntimeException exception) {
            throw new InvalidAuditEventException("IAM Password payload 不是合法 JSON", exception);
        }
        var record = validator.validate(message.topic(), message.key(),
                IamPasswordEventValidator.CONSUMER_NAME, message.value());
        service.record(IamPasswordEventValidator.CONSUMER_NAME, record);
        acknowledgment.acknowledge();
    }
}
