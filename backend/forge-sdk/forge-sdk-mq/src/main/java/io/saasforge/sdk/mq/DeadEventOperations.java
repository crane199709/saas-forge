package io.saasforge.sdk.mq;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import io.saasforge.sdk.core.api.PageMeta;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 服务内 Dead Event 运维入口，查询不依赖 MQ 连接。 */
public final class DeadEventOperations {
    private final DeadEventMapper mapper;

    private final TransactionTemplate transaction;
    private final ObjectProvider<EventConsumer> consumers;

    DeadEventOperations(
            DeadEventMapper mapper, PlatformTransactionManager manager, ObjectProvider<EventConsumer> consumers) {
        this.mapper = mapper;
        this.consumers = consumers;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 读取单条死信元数据，记录不存在时返回业务错误。 */
    public DeadEventResponse get(UUID id) {
        DeadEventResponse event = mapper.find(id);
        if (event == null) throw new ForgeException(DeadEventErrorCode.NOT_FOUND);
        return event;
    }

    /** 恢复原租户并直接调用 Handler；处置标记、去重登记与业务写入在同一事务提交。 */
    public DeadEventResponse replay(UUID id, String reason) {
        String explanation = reason(reason);
        if (!"PENDING".equals(get(id).status())) throw new ForgeException(DeadEventErrorCode.ALREADY_HANDLED);
        DeadEventEntry event = mapper.replayInput(id);
        if (event == null) throw new ForgeException(DeadEventErrorCode.NOT_FOUND);
        var result = new AtomicReference<DeadEventResponse>();
        Runnable work = () -> transaction.executeWithoutResult(ignored -> {
            if (mapper.resolve(id, "REPLAYED", explanation) != 1)
                throw new ForgeException(DeadEventErrorCode.ALREADY_HANDLED);
            DeadEventResponse resolved = mapper.find(id);
            EventConsumer consumer = consumers.getIfAvailable();
            if (consumer == null) throw new IllegalStateException("No EventHandler is registered");
            consumer.handle(
                    event.consumerGroup(),
                    event.eventId(),
                    event.originalTenantId(),
                    event.topic(),
                    event.tag(),
                    event.payload());
            // TenantDeleted 的清理器可能连同这条死信一起删除；成功响应仍返回已提交处置的元数据。
            result.set(resolved);
        });
        try {
            if (event.originalTenantId() == null) {
                if (CurrentTenant.find().isPresent())
                    throw new IllegalStateException("Tenantless replay requires an unbound tenant context");
                work.run();
            } else {
                // 行锁与业务连接都必须在恢复租户之后获取，不能先在无租户事务中锁记录。
                CurrentTenant.run(UUID.fromString(event.originalTenantId()), work);
            }
        } catch (RuntimeException failure) {
            if (failure instanceof ForgeException business
                    && business.errorCode() == DeadEventErrorCode.ALREADY_HANDLED) throw business;
            // Handler 事务已回滚，再独立记录新错误；并发已处置的记录不能退回待处置。
            String error = failure.toString();
            transaction.executeWithoutResult(ignored ->
                    mapper.updateError(id, error.substring(0, Math.min(error.length(), 1000))));
            throw new ForgeException(DeadEventErrorCode.REPLAY_FAILED);
        }
        return result.get();
    }

    /** 填写原因后放弃待处置事件；条件更新保证与重放互斥。 */
    public DeadEventResponse abandon(UUID id, String reason) {
        String explanation = reason(reason);
        get(id);
        return transaction.execute(ignored -> {
            if (mapper.resolve(id, "ABANDONED", explanation) != 1)
                throw new ForgeException(DeadEventErrorCode.ALREADY_HANDLED);
            return mapper.find(id);
        });
    }

    /** 清理处置完成已满 90 天的记录；待处置事件永不按保留期删除，可重复并发执行。 */
    public int purgeResolved() {
        return mapper.deleteResolvedBefore(Instant.now().minus(90, ChronoUnit.DAYS));
    }

    /** 租户删除事务中清理该租户的基础设施死信；调用方须已绑定同一租户，不影响其他租户或全局事件。 */
    public void cleanTenant(UUID tenantId) {
        if (!CurrentTenant.require().equals(tenantId))
            throw new IllegalStateException("Tenant cleanup context does not match");
        mapper.deleteTenant(tenantId);
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) throw new ForgeException(DeadEventErrorCode.REASON_REQUIRED);
        return reason.strip();
    }

    /** 按状态查询元数据，页码从 1 开始，每页 1 到 100 条；不受租户和 Data Policy 过滤。 */
    public ApiResponse<List<DeadEventResponse>> list(String status, int page, int size) {
        if (page < 1
                || size < 1
                || size > 100
                || (status != null
                        && !List.of("PENDING", "REPLAYED", "ABANDONED").contains(status))) {
            throw new ForgeException(FrameworkErrorCode.REQUEST_INVALID);
        }
        return ApiResponse.page(
                mapper.list(status, (long) (page - 1) * size, size), PageMeta.of(page, size, mapper.count(status)));
    }
}
