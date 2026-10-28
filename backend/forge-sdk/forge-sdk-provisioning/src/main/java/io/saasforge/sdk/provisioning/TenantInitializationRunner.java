package io.saasforge.sdk.provisioning;

import io.saasforge.sdk.mq.EventPublisher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 在消费事务内执行本服务的全部 {@link TenantInitializer} 并回报结果。
 *
 * <p>初始化失败不抛出：保存点回滚初始化数据后，消费事务照常提交失败回报，消息不再由 RocketMQ 重投，
 * 改由 Operator 在平台确认后重试，避免持续失败的初始化无限重投。
 */
final class TenantInitializationRunner {
    private static final Logger log = LoggerFactory.getLogger(TenantInitializationRunner.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final List<TenantInitializer> initializers;
    // PROPAGATION_NESTED：在消费事务中以保存点执行，失败只回滚初始化数据
    private final TransactionTemplate savepoint;
    private final EventPublisher publisher;
    private final String service;

    TenantInitializationRunner(
            List<TenantInitializer> initializers,
            TransactionTemplate savepoint,
            EventPublisher publisher,
            String service) {
        this.initializers = initializers;
        this.savepoint = savepoint;
        this.publisher = publisher;
        this.service = service;
    }

    String service() {
        return service;
    }

    void run(TenantCreated tenant) {
        String error = null;
        try {
            savepoint.executeWithoutResult(
                    status -> initializers.forEach(initializer -> initializer.initialize(tenant)));
        } catch (RuntimeException e) {
            log.error("Failed to initialize tenant {} in service {}", tenant.tenantId(), service, e);
            error = truncate(String.valueOf(e));
        }
        publisher.publish(
                TenantCreated.TOPIC,
                new TenantInitializationReported(tenant.tenantId(), service, error == null, error));
    }

    private static String truncate(String error) {
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
