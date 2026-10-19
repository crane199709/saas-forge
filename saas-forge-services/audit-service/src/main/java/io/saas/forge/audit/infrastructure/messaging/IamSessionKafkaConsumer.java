package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecordService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class IamSessionKafkaConsumer {
    public static final String LISTENER_ID = "audit-iam-session-events";

    private final IamSessionEventValidator validator;
    private final AuditRecordService service;

    public IamSessionKafkaConsumer(IamSessionEventValidator validator, AuditRecordService service) {
        this.validator = validator;
        this.service = service;
    }

    /** Acknowledgment 必须发生在本地事务方法返回之后，以便提交后故障由去重键吸收。 */
    @KafkaListener(
            id = LISTENER_ID,
            topics = "${saas.forge.audit.iam-session-topic}",
            groupId = SessionStartedEventValidator.CONSUMER_NAME)
    public void consume(ConsumerRecord<String, String> message, Acknowledgment acknowledgment) {
        if (IamPasswordEventValidator.isPasswordEvent(message.value())) {
            acknowledgment.acknowledge();
            return;
        }
        var record = validator.validate(
                message.topic(), message.key(), SessionStartedEventValidator.CONSUMER_NAME, message.value());
        service.record(SessionStartedEventValidator.CONSUMER_NAME, record);
        acknowledgment.acknowledge();
    }
}
