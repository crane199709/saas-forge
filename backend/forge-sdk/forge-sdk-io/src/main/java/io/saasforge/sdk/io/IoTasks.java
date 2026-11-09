package io.saasforge.sdk.io;

import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.BackgroundJwtAuthentication;
import io.saasforge.sdk.security.TenantContext;
import io.saasforge.sdk.storage.DownloadTicket;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.tenant.TenantWriteGuard;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** 本服务的异步任务入口；仅接收已认证 Membership 的提交，不使用 MQ 或自动重试。 */
public final class IoTasks implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(IoTasks.class);
    private final IoTaskMapper mapper;
    private final ObjectStorage storage;
    private final DataPermission permissions;
    private final AuditRecorder audit;
    private final MessageSource messages;
    private final StringRedisTemplate redis;
    private final String tenantKey;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore slots;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final TransactionTemplate transaction;
    private final IoProperties properties;

    IoTasks(
            IoTaskMapper mapper,
            ObjectStorage storage,
            DataPermission permissions,
            AuditRecorder audit,
            MessageSource messages,
            StringRedisTemplate redis,
            String service,
            TransactionTemplate transaction,
            IoProperties properties) {
        this.mapper = mapper;
        this.storage = storage;
        this.permissions = permissions;
        this.audit = audit;
        this.messages = messages;
        this.redis = redis;
        this.transaction = transaction;
        this.properties = properties;
        tenantKey = "forge:io:" + service + ":tenants";
        // 信号量才是容量上限（执行中 + 排队）。槽位在任务收尾时释放，此时工作线程还没回去取下一个任务，
        // 所以队列按信号量总数开，持有槽位的提交不会被 AbortPolicy 拒绝。
        slots = new Semaphore(properties.workers() + properties.queueCapacity());
        workers = new ThreadPoolExecutor(
                properties.workers(),
                properties.workers(),
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.workers() + properties.queueCapacity()),
                new ThreadPoolExecutor.AbortPolicy());
        // 异常不能逃出：scheduleAtFixedRate 遇异常即取消后续调度，所有任务的心跳会一起停止，随后被判为中断。
        heartbeats.scheduleAtFixedRate(
                () -> pending.forEach((id, pendingTask) -> CurrentTenant.run(pendingTask.tenant(), () -> {
                    try {
                        TenantWriteGuard.exempt(() ->
                                mapper.heartbeat(id, pendingTask.progress().rows()));
                    } catch (RuntimeException error) {
                        LOG.warn("IO heartbeat failed for {}", id, error);
                    }
                })),
                properties.heartbeatInterval().toMillis(),
                properties.heartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 提交一个任务。业务方应通过 ExcelIo 提交导入与导出；此入口供需要定制处理的业务复用任务生命周期。
     *
     * @param type IMPORT 或 EXPORT
     * @param source 已按 {@link IoStoragePurposes#SOURCE} 核对用途的导入源对象，导出为 null；任务结束后删除
     * @param summary 查询条件摘要，禁止放入凭证或敏感信息
     * @param work 在提交时租户、认证与数据权限上下文中运行的处理
     * @return 等待中的任务
     */
    public IoTask submit(String type, UUID source, String summary, Work work) {
        if (!List.of("IMPORT", "EXPORT").contains(type)) throw new IllegalArgumentException("Unknown IO type");
        TenantContext owner = TenantContext.current();
        if (!owner.tenantId().equals(CurrentTenant.require())) throw new IllegalStateException("Tenant mismatch");
        var authentication = new BackgroundJwtAuthentication(
                (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication());
        var locale = LocaleContextHolder.getLocale();
        UUID id = UuidV7.generate();
        var progress = new Progress();
        Runnable execution = permissions.wrap(CurrentTenant.wrap(() -> {
            var previous = SecurityContextHolder.getContext();
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            try {
                SecurityContextHolder.setContext(context);
                LocaleContextHolder.setLocale(locale);
                run(id, type, source, summary, work, progress, owner);
            } finally {
                SecurityContextHolder.setContext(previous);
                LocaleContextHolder.resetLocaleContext();
            }
        }));
        if (!slots.tryAcquire()) throw new ForgeException(IoErrorCode.BUSY);
        try {
            // 只存租户 ID 的持久目录供 Global Task 恢复 RLS 上下文；进程重启后仍能收尾中断任务。
            redis.opsForSet().add(tenantKey, owner.tenantId().toString());
            transaction.executeWithoutResult(
                    status -> mapper.insert(id, type, owner.membershipId(), owner.identityId(), source, summary));
            IoTask task = get(id);
            pending.put(id, new Pending(owner.tenantId(), progress));
            workers.execute(execution);
            return task;
        } catch (RuntimeException error) {
            pending.remove(id);
            slots.release();
            throw error;
        }
    }

    private void run(
            UUID id, String type, UUID source, String summary, Work work, Progress progress, TenantContext owner) {
        UUID result = null;
        String code = IoErrorCode.FAILED.code();
        try {
            if (mapper.start(id, null) != 1) return;
            result = work.execute(progress);
            code = progress.errorCode;
        } catch (Exception error) {
            LOG.warn("IO task {} failed", id, error);
            code = error instanceof ForgeException forge ? forge.errorCode().code() : IoErrorCode.FAILED.code();
        } finally {
            try {
                if (source != null)
                    TenantWriteGuard.exempt(() -> transaction.executeWithoutResult(status -> {
                        storage.delete(source);
                        mapper.clearSource(id);
                    }));
            } catch (ForgeException error) {
                // 源对象可能已被 IoMaintenance 删除（如心跳超时被判中断），不必告警；未清除的 source_object_id 由它补清。
                if (!"storage.objectNotFound".equals(error.errorCode().code())) {
                    LOG.warn("Could not delete import source {}", source, error);
                }
            } catch (RuntimeException error) {
                LOG.warn("Could not delete import source {}", source, error);
            }
            try {
                String reason = code == null
                        ? null
                        : messages.getMessage(code, null, "处理失败，请重新提交", LocaleContextHolder.getLocale());
                var changedHolder = new AtomicInteger();
                String finalCode = code;
                UUID finalResult = result;
                TenantWriteGuard.exempt(() -> changedHolder.set(mapper.finish(
                        id,
                        finalCode == null ? "SUCCEEDED" : "FAILED",
                        progress.rows.get(),
                        finalCode,
                        reason,
                        finalResult,
                        null)));
                int changed = changedHolder.get();
                if (changed == 0 && result != null) storage.delete(result);
                var event = AuditEvent.builder(
                                AuditRealm.TENANT,
                                AuditCategory.OPERATION,
                                "IMPORT".equals(type) ? "import.run" : "export.run")
                        .tenant(owner.tenantId())
                        .actor(owner.identityId())
                        .resource("ioTask", id)
                        .detail("rowCount", progress.rows.get())
                        .detail("query", summary);
                if (code != null) event.failure(code);
                audit.record(event.build());
            } finally {
                pending.remove(id);
                slots.release();
            }
        }
    }

    /** 返回本人的任务；其他成员或其他租户与不存在的任务一样返回 404。 */
    public IoTask get(UUID id) {
        IoTask task = mapper.own(id, TenantContext.current().membershipId());
        if (task == null) throw new ForgeException(IoErrorCode.NOT_FOUND);
        return task;
    }
    /** 按创建时间倒序列出本人任务。分页参数由 HTTP 层或调用方校验。 */
    public List<IoTask> list(int size, long offset) {
        return mapper.list(TenantContext.current().membershipId(), size, offset);
    }
    /** 本人任务总数。 */
    public long count() {
        return mapper.count(TenantContext.current().membershipId());
    }
    /** 仅结果未过期且属于当前提交人时签发下载票据。失败任务也可以下载错误报告。 */
    public DownloadTicket download(UUID id) {
        IoTask task = get(id);
        if (task.resultObjectId() == null
                || task.finishedAt() == null
                || task.finishedAt().isBefore(OffsetDateTime.now().minus(properties.retention())))
            throw new ForgeException(IoErrorCode.RESULT_UNAVAILABLE);
        return storage.prepareDownload(IoStoragePurposes.RESULT, task.resultObjectId());
    }

    String tenantKey() {
        return tenantKey;
    }
    /** 停止接收提交，等待正在执行的本地任务结束。 */
    @Override
    public void close() {
        workers.shutdown();
        try {
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            workers.shutdownNow();
            heartbeats.shutdownNow();
        }
    }

    private record Pending(UUID tenant, Progress progress) {}

    /** 异步处理边界。 */
    @FunctionalInterface
    public interface Work {
        /** 返回按 {@link IoStoragePurposes#RESULT} 写入的结果文件对象 ID；无结果文件返回 null。 */
        UUID execute(Progress progress) throws Exception;
    }
    /** 供处理器登记已处理行数；不会缓存业务行。 */
    public static final class Progress {
        private final AtomicLong rows = new AtomicLong();
        private String errorCode;
        /** 处理完毕但校验失败时保留结果文件，并将任务标为失败。 */
        public void fail(String code) {
            errorCode = code;
        }
        /** 登记下一行，返回累计行数。 */
        public long next() {
            return rows.incrementAndGet();
        }
        /** 当前累计行数。 */
        public long rows() {
            return rows.get();
        }
    }
}
