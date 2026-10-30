package io.saasforge.sdk.audit;

import io.micrometer.tracing.Tracer;
import io.saasforge.sdk.core.api.ErrorEnvelopes;
import io.saasforge.sdk.mq.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 记录 Audit Event：写入 Outbox 后由 SDK 投递器异步发送到 {@link AuditEvent#TOPIC}。
 *
 * <p>审计不能影响业务请求：事件在独立事务中写入 Outbox，写入失败只记日志，不向调用方抛出；业务事务回滚也不会连带撤销
 * 已记录的失败事件。
 */
public final class AuditRecorder {
    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final EventPublisher publisher;
    private final TransactionTemplate independent;
    private final String service;
    private final ObjectProvider<Tracer> tracer;

    AuditRecorder(
            EventPublisher publisher, TransactionTemplate independent, String service, ObjectProvider<Tracer> tracer) {
        this.publisher = publisher;
        this.independent = independent;
        this.service = service;
        this.tracer = tracer;
    }

    /**
     * 记录一条事件。在事务中调用时等到事务提交后才写入，事务回滚则不记录，避免审计出没有发生的变更；不在事务中时立即写入。
     *
     * @param event 事件
     */
    public void record(AuditEvent event) {
        AuditEvent sourced = event.withSource(service, ErrorEnvelopes.traceId(tracer.getIfAvailable()));
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            write(sourced);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                write(sourced);
            }
        });
    }

    /**
     * 立即在独立事务中记录，不等待调用方事务：失败事件所在的业务事务多半随异常回滚，等待提交会让它丢失。
     *
     * @param event 事件
     */
    void recordNow(AuditEvent event) {
        write(event.withSource(service, ErrorEnvelopes.traceId(tracer.getIfAvailable())));
    }

    // 独立事务（REQUIRES_NEW）：事务提交回调中原事务的连接仍绑定在线程上，不另开事务的写入会落入已提交的事务而丢失。
    private void write(AuditEvent event) {
        try {
            independent.executeWithoutResult(status -> publisher.publish(AuditEvent.TOPIC, event));
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to record audit event {} ({}, outcome {})", event.id(), event.action(), event.outcome(), e);
        }
    }
}
