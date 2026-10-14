package io.saas.forge.audit.infrastructure.messaging;

import io.saas.forge.audit.application.AuditRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 按工程注册的 type 将 IAM Session Consumer 消息路由到对应的严格契约校验器。 */
@Component
public class IamSessionEventValidator {
    private final ObjectMapper objectMapper;
    private final SessionStartedEventValidator sessionStarted;
    private final TenantContextSwitchedEventValidator tenantContextSwitched;
    private final PasswordRecoveredEventValidator passwordRecovered;
    private final PlatformAdminGrantedEventValidator platformAdminGranted;

    public IamSessionEventValidator(
            ObjectMapper objectMapper,
            SessionStartedEventValidator sessionStarted,
            TenantContextSwitchedEventValidator tenantContextSwitched,
            PasswordRecoveredEventValidator passwordRecovered,
            PlatformAdminGrantedEventValidator platformAdminGranted) {
        this.objectMapper = objectMapper;
        this.sessionStarted = sessionStarted;
        this.tenantContextSwitched = tenantContextSwitched;
        this.passwordRecovered = passwordRecovered;
        this.platformAdminGranted = platformAdminGranted;
    }

    public AuditRecord validate(String topic, String orderingKey, String consumerName, String payload) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(payload);
        } catch (RuntimeException exception) {
            throw new InvalidAuditEventException("IAM Session payload 不是合法 JSON", exception);
        }
        JsonNode type = envelope == null ? null : envelope.get("type");
        if (type == null || !type.isTextual()) {
            throw new InvalidAuditEventException("IAM Session 契约字段不合法: type");
        }
        return switch (type.asText()) {
            case SessionStartedEventValidator.EVENT_TYPE ->
                    sessionStarted.validate(topic, orderingKey, consumerName, payload);
            case TenantContextSwitchedEventValidator.EVENT_TYPE ->
                    tenantContextSwitched.validate(topic, orderingKey, consumerName, payload);
            case PasswordRecoveredEventValidator.EVENT_TYPE ->
                    passwordRecovered.validate(topic, orderingKey, consumerName, payload);
            case PlatformAdminGrantedEventValidator.EVENT_TYPE ->
                    platformAdminGranted.validate(topic, orderingKey, consumerName, payload);
            default -> throw new InvalidAuditEventException("IAM Session type 未获当前消费者处理授权");
        };
    }
}
