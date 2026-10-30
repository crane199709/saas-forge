package io.saasforge.iam.audit.service;

import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.mq.EventHandler;
import org.springframework.stereotype.Service;

/**
 * 订阅各服务（含 iam 自身）产出的 Audit Event 并写入审计表。写入失败时消息由 RocketMQ 重投，不影响产出方的业务请求；
 * 重复送达由 SDK 按事件 ID 去重，主键冲突时也会忽略。
 */
@Service
class AuditEventHandler implements EventHandler<AuditEvent> {
    private final AuditService audits;

    AuditEventHandler(AuditService audits) {
        this.audits = audits;
    }

    @Override
    public String topic() {
        return AuditEvent.TOPIC;
    }

    @Override
    public Class<AuditEvent> eventType() {
        return AuditEvent.class;
    }

    @Override
    public void handle(AuditEvent event) {
        audits.store(event);
    }
}
