package io.saasforge.sdk.storage;

import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * 当前租户的对象存储，业务服务注入使用。
 *
 * <p>对象 key 为 {@code {tenant_id}/{service}/{objectId}}，由 SDK 按当前租户与 {@code spring.application.name} 生成，业务代码
 * 只拿到对象 ID，无法指定或越过前缀。对象登记在 {@code forge_storage_object} 中按租户隔离，以其他租户的对象 ID 访问与对象不存在
 * 一样得到 {@link StorageErrorCode#OBJECT_NOT_FOUND}。文件内容不经过服务：上传与下载都由客户端凭预签名 URL 直接访问存储。
 *
 * <p>上传分两步：{@link #prepareUpload} 校验存储容量 Quota、登记对象并签发上传 URL；客户端上传后调用 {@link #completeUpload}
 * 确认，对象此后才可下载。已用容量包含上传申请与失效对象，物理删除后才释放；并发申请不会合计超出 Quota。
 *
 * <p>所有方法都要求已绑定租户上下文。
 */
public class ObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    /** S3 单次 PUT 的大小上限。 */
    static final long MAX_OBJECT_SIZE = StoragePurpose.MAX_OBJECT_SIZE;

    /** 与 {@code forge_storage_object} 的列宽一致。 */
    private static final int MAX_NAME_LENGTH = 255;

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    // 由浏览器等客户端自行设置，不能也不必由调用方显式携带
    private static final List<String> IMPLICIT_HEADERS = List.of("host", "content-length");

    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final StorageProperties properties;
    private final String service;
    private final Clock clock;
    private final AuditRecorder audit;
    private final CurrentActorProvider actors;

    ObjectStorage(
            S3Client s3,
            S3Presigner presigner,
            StorageObjectMapper mapper,
            TransactionTemplate transaction,
            StorageProperties properties,
            String service,
            Clock clock,
            AuditRecorder audit,
            CurrentActorProvider actors) {
        if (service.isBlank() || service.contains("/")) {
            throw new IllegalArgumentException(
                    "spring.application.name must be a single object key segment: " + service);
        }
        this.s3 = s3;
        this.presigner = presigner;
        this.mapper = mapper;
        this.transaction = transaction;
        this.properties = properties;
        this.service = service;
        this.clock = clock;
        this.audit = audit;
        this.actors = actors;
    }

    /**
     * 按声明用途校验文件并申请上传，文件内容类型不用于扩展名判定。
     *
     * @param purpose 服务声明的用途
     * @param filename 原文件名，扩展名忽略大小写
     * @param contentType 内容类型，空值使用 application/octet-stream
     * @param size 文件大小（字节）
     * @return 上传凭证
     * @throws ForgeException 参数非法为 request.invalid；扩展名不符为 {@link StorageErrorCode#EXTENSION_NOT_ALLOWED}；
     *     超出用途上限为 {@link StorageErrorCode#FILE_TOO_LARGE}；超出 Quota 为 {@link StorageErrorCode#QUOTA_EXCEEDED}
     */
    public UploadTicket prepareUpload(StoragePurpose purpose, String filename, String contentType, long size) {
        requireFile(purpose, filename, size);
        String type = contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
        requireName(type, "contentType");
        if (size <= 0 || size > MAX_OBJECT_SIZE) {
            throw ForgeException.withErrorMessage(
                    FrameworkErrorCode.REQUEST_INVALID, "size must be between 1 and " + MAX_OBJECT_SIZE + " bytes");
        }
        UUID id = UuidV7.generate();
        String key = prefix(CurrentTenant.require()) + id;
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = now.plus(properties.uploadUrlTtl());
        transaction.executeWithoutResult(status -> {
            Long quota = mapper.lockQuota();
            if (quota != null && mapper.usedBytes(null) + size > quota) {
                throw quotaExceeded(quota);
            }
            mapper.insertPending(
                    id, key, purpose.code(), filename, type, size, expiresAt, retentionDeadline(purpose, now));
        });
        PresignedPutObjectRequest presigned =
                presigner.presignPutObject(request -> request.signatureDuration(properties.uploadUrlTtl())
                        .putObjectRequest(put -> put.bucket(properties.bucket())
                                .key(key)
                                .contentType(type)
                                .contentLength(size)));
        var headers = new HashMap<String, String>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!IMPLICIT_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, String.join(",", values));
            }
        });
        return new UploadTicket(id, URI.create(presigned.url().toString()), headers, expiresAt);
    }

    /**
     * 确认上传：校验存储中已有该对象且大小一致，并再次校验 Quota（申请可能已过期，或 Plan 已降级），通过后对象可下载。
     * 已确认的对象重复确认直接返回。
     *
     * @param objectId {@link UploadTicket#objectId()}
     * @return 已确认的对象
     * @throws ForgeException 对象不存在或属于其他租户时为 {@link StorageErrorCode#OBJECT_NOT_FOUND}；尚未上传或大小不一致时为
     *     {@link StorageErrorCode#UPLOAD_INCOMPLETE}；超出 Quota 时为 {@link StorageErrorCode#QUOTA_EXCEEDED}，对象进入失效治理
     */
    public StoredObject completeUpload(UUID objectId) {
        StorageObjectRecord object = mapper.findById(objectId);
        if (object == null) {
            throw notFound(objectId);
        }
        if (object.stored()) {
            return object.toStoredObject();
        }
        if (object.status() != StorageObjectStatus.PENDING) {
            throw notFound(objectId);
        }
        HeadObjectResponse head;
        try {
            head = s3.headObject(request -> request.bucket(properties.bucket()).key(object.objectKey()));
        } catch (NoSuchKeyException e) {
            throw ForgeException.withErrorMessage(
                    StorageErrorCode.UPLOAD_INCOMPLETE, "Object " + objectId + " has not been uploaded");
        }
        if (head.contentLength() != object.size()) {
            throw ForgeException.withErrorMessage(
                    StorageErrorCode.UPLOAD_INCOMPLETE,
                    "Object " + objectId + " has " + head.contentLength() + " bytes, expected " + object.size());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Long rejectedQuota = transaction.execute(status -> {
            Long quota = mapper.lockQuota();
            if (quota != null && mapper.usedBytes(objectId) + object.size() > quota) {
                if (mapper.markExpired(objectId, now) == 1) {
                    recordAudit("storage.expire", object, "quota", object.retentionExpiresAt());
                }
                return quota;
            }
            mapper.markStored(objectId, now);
            return null;
        });
        if (rejectedQuota != null) {
            // 拒绝确认也不能绕过宽限期；外层业务事务若回滚，原 PENDING 登记仍由后续治理处理。
            throw quotaExceeded(rejectedQuota);
        }
        return get(objectId);
    }

    /**
     * 读取当前租户已确认上传对象的元数据，供管理与清理使用。业务绑定或处理文件须使用带用途的 {@link #get(StoragePurpose, UUID)}。
     *
     * @param objectId 对象 ID
     * @return 对象
     * @throws ForgeException 对象不存在、未确认上传或属于其他租户时为 {@link StorageErrorCode#OBJECT_NOT_FOUND}
     */
    public StoredObject get(UUID objectId) {
        return requireStored(objectId).toStoredObject();
    }

    /**
     * 核对用途后读取当前租户已确认对象，供导入或绑定业务资源之前检查。
     *
     * <p>在可写事务内读取时持对象行锁至事务结束；建立业务引用须在同一事务内先调用本方法再写入引用，
     * 与治理的最终引用检查和失效互斥。无事务或只读事务只读取，不为后续绑定提供保证。
     *
     * <p>存量 unspecified 对象不匹配任何可声明用途；服务必须先按可信业务关联显式迁移用途。
     *
     * @param purpose 业务预期用途
     * @param objectId 对象 ID
     * @return 已确认对象
     * @throws ForgeException 不存在、未确认或跨租户为 {@link StorageErrorCode#OBJECT_NOT_FOUND}；用途不同为
     *     {@link StorageErrorCode#PURPOSE_MISMATCH}
     */
    public StoredObject get(StoragePurpose purpose, UUID objectId) {
        Objects.requireNonNull(purpose, "purpose");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            StorageObjectRecord object = mapper.findByIdForUpdate(objectId);
            if (object == null || !object.stored()) {
                throw notFound(objectId);
            }
            if (!purpose.code().equals(object.purpose())) {
                throw new ForgeException(StorageErrorCode.PURPOSE_MISMATCH);
            }
            return object.toStoredObject();
        }
        return requirePurpose(purpose, objectId).toStoredObject();
    }

    /**
     * 打开当前租户已确认对象的内容流。调用方负责关闭流，内容不会整体载入内存。
     * 业务处理前须使用 {@link #get(StoragePurpose, UUID)} 核对用途。
     *
     * @param objectId 对象 ID
     * @return 对象内容
     * @throws ForgeException 对象不存在、未确认或前缀不属于当前租户时为 OBJECT_NOT_FOUND
     */
    public InputStream open(UUID objectId) {
        StorageObjectRecord object = requireStored(objectId);
        if (!object.objectKey().startsWith(prefix(CurrentTenant.require()))) {
            throw notFound(objectId);
        }
        try {
            return s3.getObject(request -> request.bucket(properties.bucket()).key(object.objectKey()));
        } catch (NoSuchKeyException error) {
            throw notFound(objectId);
        }
    }

    /**
     * 按声明用途校验并流式写入，成功后对象可直接下载。大小须提前确定，调用方负责关闭输入流。
     * Quota 检查、登记与确认在同一事务内，失败或外层事务回滚时清理存储对象。
     *
     * @param purpose 服务声明的用途
     * @param filename 下载文件名，扩展名忽略大小写
     * @param contentType 内容类型，空值使用 application/octet-stream
     * @param size 文件大小（字节），须与内容一致且大于零
     * @param content 内容流
     * @return 已确认对象
     * @throws ForgeException 参数非法为 request.invalid；扩展名不符为 {@link StorageErrorCode#EXTENSION_NOT_ALLOWED}；
     *     超出用途上限为 {@link StorageErrorCode#FILE_TOO_LARGE}；超出 Quota 为 {@link StorageErrorCode#QUOTA_EXCEEDED}
     */
    public StoredObject write(
            StoragePurpose purpose, String filename, String contentType, long size, InputStream content) {
        requireFile(purpose, filename, size);
        String type = contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
        requireName(type, "contentType");
        if (size <= 0 || size > MAX_OBJECT_SIZE) {
            throw ForgeException.withErrorMessage(FrameworkErrorCode.REQUEST_INVALID, "Invalid object size");
        }
        UUID id = UuidV7.generate();
        String key = prefix(CurrentTenant.require()) + id;
        return transaction.execute(status -> {
            var now = OffsetDateTime.now(clock);
            Long quota = mapper.lockQuota();
            if (quota != null && mapper.usedBytes(null) + size > quota) {
                throw quotaExceeded(quota);
            }
            mapper.insertPending(
                    id,
                    key,
                    purpose.code(),
                    filename,
                    type,
                    size,
                    now.plus(properties.uploadUrlTtl()),
                    retentionDeadline(purpose, now));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completion) {
                    if (completion != STATUS_COMMITTED) {
                        try {
                            s3.deleteObject(request ->
                                    request.bucket(properties.bucket()).key(key));
                        } catch (SdkException error) {
                            log.warn("Failed to clean rolled-back object {}", key, error);
                        }
                    }
                }
            });
            s3.putObject(
                    request -> request.bucket(properties.bucket()).key(key).contentType(type),
                    RequestBody.fromInputStream(content, size));
            mapper.markStored(id, now);
            return get(id);
        });
    }

    /**
     * 核对当前租户对象用途后签发下载 URL，有效期由用途声明决定。
     *
     * <p>用途编码只用于防止业务选错文件规则；业务资源的权限仍由调用方在申请下载前校验。
     * unspecified 存量对象须先按业务关联迁移用途，不能以未知用途下载。
     *
     * @param purpose 业务预期用途
     * @param objectId 对象 ID
     * @return 下载凭证
     * @throws ForgeException 不存在、未确认或跨租户为 {@link StorageErrorCode#OBJECT_NOT_FOUND}；用途不同为
     *     {@link StorageErrorCode#PURPOSE_MISMATCH}
     */
    public DownloadTicket prepareDownload(StoragePurpose purpose, UUID objectId) {
        StorageObjectRecord object = requirePurpose(purpose, objectId);
        Duration ttl = purpose.downloadUrlTtl();
        // 登记已按租户隔离；再核对一次前缀，签名的 key 绝不会落在当前租户之外
        if (!object.objectKey().startsWith(prefix(CurrentTenant.require()))) {
            throw notFound(objectId);
        }
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(ttl);
        PresignedGetObjectRequest presigned = presigner.presignGetObject(request -> request.signatureDuration(ttl)
                .getObjectRequest(get -> get.bucket(properties.bucket())
                        .key(object.objectKey())
                        .responseContentType(object.contentType())
                        .responseContentDisposition(contentDisposition(object.filename()))));
        return new DownloadTicket(URI.create(presigned.url().toString()), expiresAt);
    }

    /**
     * 将当前租户对象标记失效，七天宽限期后经引用检查才物理删除。已确认与未确认上传的对象都可失效。
     * 登记随调用方事务提交或回滚；宽限期内保留存储对象和容量，重复调用不延后宽限期。
     * 已签发的下载凭证在原有效期内仍然有效。
     *
     * @param objectId 对象 ID
     * @throws ForgeException 对象不存在或属于其他租户时为 {@link StorageErrorCode#OBJECT_NOT_FOUND}
     */
    public void delete(UUID objectId) {
        transaction.executeWithoutResult(status -> {
            StorageObjectRecord object = mapper.findByIdForUpdate(objectId);
            if (object == null) {
                throw notFound(objectId);
            }
            if (mapper.markExpired(objectId, OffsetDateTime.now(clock)) == 1) {
                recordAudit("storage.expire", object, "delete", object.retentionExpiresAt());
            }
        });
    }

    /**
     * 读取当前租户文件的生命周期，包括尚未确认和已失效的登记。
     *
     * @param objectId 对象 ID
     * @return 生命周期元数据
     * @throws ForgeException 对象不存在或属于其他租户时为 {@link StorageErrorCode#OBJECT_NOT_FOUND}
     */
    public StorageObjectLifecycle lifecycle(UUID objectId) {
        StorageObjectRecord object = mapper.findById(objectId);
        if (object == null) {
            throw notFound(objectId);
        }
        return object.lifecycle();
    }

    /**
     * 对已到保留截止时间或上传凭证已过期的未确认文件标记失效；自动治理须在持候选对象行锁的同一事务内先检查业务引用。
     * 不删除存储对象，不释放容量；重复调用不重置七天宽限期。
     *
     * @param objectId 对象 ID
     * @return 本次是否将文件标记为失效
     */
    public boolean expire(UUID objectId) {
        return transaction.execute(status -> {
            StorageObjectRecord object = mapper.findByIdForUpdate(objectId);
            if (object == null || mapper.expireDue(objectId, OffsetDateTime.now(clock)) != 1) {
                return false;
            }
            recordAudit("storage.expire", object, "deadline", object.retentionExpiresAt());
            return true;
        });
    }

    /**
     * 在七天宽限期内恢复当前租户文件，按服务声明从当前时间重新计算保留截止时间。
     * 恢复未确认上传时仍须完成原上传并确认，不会直接变为可下载文件。
     *
     * @param purpose 可信服务声明的原文件用途
     * @param objectId 对象 ID
     * @return 恢复后的生命周期
     * @throws ForgeException 用途不同为 {@link StorageErrorCode#PURPOSE_MISMATCH}；非失效状态或宽限期结束为
     *     {@link StorageErrorCode#RETENTION_UNAVAILABLE}
     */
    public StorageObjectLifecycle restore(StoragePurpose purpose, UUID objectId) {
        Objects.requireNonNull(purpose, "purpose");
        return transaction.execute(status -> {
            StorageObjectRecord object = mapper.findByIdForUpdate(objectId);
            if (object == null) {
                throw notFound(objectId);
            }
            if (!purpose.code().equals(object.purpose())) {
                throw new ForgeException(StorageErrorCode.PURPOSE_MISMATCH);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (object.status() != StorageObjectStatus.EXPIRED
                    || !now.isBefore(object.expiredAt().plus(StorageObjectLifecycle.PURGE_GRACE_PERIOD))) {
                throw new ForgeException(StorageErrorCode.RETENTION_UNAVAILABLE);
            }
            OffsetDateTime deadline = retentionDeadline(purpose, now);
            mapper.renewRetention(objectId, deadline);
            recordAudit("storage.retentionExtend", object, "restore", deadline);
            return mapper.findById(objectId).lifecycle();
        });
    }

    /**
     * 延长当前租户文件的保留截止时间，失效文件在七天宽限期内随之恢复原上传状态。
     * 永久保留文件无须延长；延长不会改变存储内容与已用容量。
     *
     * @param objectId 对象 ID
     * @param retentionExpiresAt 新截止时间，必须晚于现在与原截止时间
     * @return 延长后的生命周期
     * @throws ForgeException 截止时间非法为 request.invalid；不可延长的状态或宽限期结束为
     *     {@link StorageErrorCode#RETENTION_UNAVAILABLE}
     */
    public StorageObjectLifecycle extendRetention(UUID objectId, OffsetDateTime retentionExpiresAt) {
        return transaction.execute(status -> {
            StorageObjectRecord object = mapper.findByIdForUpdate(objectId);
            if (object == null) {
                throw notFound(objectId);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            boolean active =
                    object.status() == StorageObjectStatus.STORED || object.status() == StorageObjectStatus.PENDING;
            boolean restorable = object.status() == StorageObjectStatus.EXPIRED
                    && now.isBefore(object.expiredAt().plus(StorageObjectLifecycle.PURGE_GRACE_PERIOD));
            if ((!active && !restorable) || object.retentionExpiresAt() == null) {
                throw new ForgeException(StorageErrorCode.RETENTION_UNAVAILABLE);
            }
            if (retentionExpiresAt == null
                    || !retentionExpiresAt.isAfter(now)
                    || !retentionExpiresAt.isAfter(object.retentionExpiresAt())) {
                throw ForgeException.withErrorMessage(
                        FrameworkErrorCode.REQUEST_INVALID,
                        "Retention deadline must be later than now and the current deadline");
            }
            mapper.renewRetention(objectId, retentionExpiresAt);
            recordAudit("storage.retentionExtend", object, "extend", retentionExpiresAt);
            return mapper.findById(objectId).lifecycle();
        });
    }

    // 与 restore/extendRetention 共用对象行锁；S3 成功之前绝不移除登记或释放容量。
    void purge(UUID objectId, String purpose) {
        transaction.executeWithoutResult(status -> {
            StorageObjectRecord object = mapper.findByIdForUpdate(objectId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (object == null
                    || !purpose.equals(object.purpose())
                    || (object.status() != StorageObjectStatus.EXPIRED
                            && object.status() != StorageObjectStatus.PURGE_PENDING)
                    || object.expiredAt() == null
                    || now.isBefore(object.expiredAt().plus(StorageObjectLifecycle.PURGE_GRACE_PERIOD))
                    || now.isBefore(object.uploadExpiresAt())) {
                return;
            }
            // 租户 RLS 之外再限制 S3 的租户/服务前缀，损坏登记也不能删除别处的对象。
            if (!object.objectKey().equals(prefix(CurrentTenant.require()) + object.id())) {
                log.warn(
                        "Storage retention skipped tenant={} purpose={} phase=purge reason=invalid-key",
                        CurrentTenant.require(),
                        purpose);
                return;
            }
            mapper.markPurgePending(objectId);
            try {
                s3.deleteObject(request -> request.bucket(properties.bucket()).key(object.objectKey()));
            } catch (SdkException error) {
                // 在事务内保留 PURGE_PENDING，下一轮重复幂等删除；不输出客户端异常中的地址或凭证。
                log.warn(
                        "Storage retention skipped tenant={} purpose={} phase=purge reason=storage-failure",
                        CurrentTenant.require(),
                        purpose);
                return;
            }
            mapper.delete(objectId);
            recordAudit("storage.purge", object, "grace-ended", object.retentionExpiresAt());
        });
    }

    private void recordAudit(String action, StorageObjectRecord object, String reason, OffsetDateTime deadline) {
        audit.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.OPERATION, action)
                .tenant(CurrentTenant.require())
                .actor(actors.currentActor().orElse(null))
                .resource("storageObject", object.id())
                .detail("purpose", object.purpose())
                .detail("reason", reason)
                .detail("previousStatus", object.status().name())
                .detail("previousRetentionExpiresAt", object.retentionExpiresAt())
                .detail("retentionExpiresAt", deadline)
                .build());
    }

    private static OffsetDateTime retentionDeadline(StoragePurpose purpose, OffsetDateTime now) {
        return purpose.retentionDays() == null ? null : now.plusDays(purpose.retentionDays());
    }

    /**
     * 当前租户在本服务中的存储用量。
     *
     * @return 已用容量与 Quota
     */
    public StorageUsage usage() {
        return new StorageUsage(mapper.usedBytes(null), mapper.quota());
    }

    private StorageObjectRecord requirePurpose(StoragePurpose purpose, UUID objectId) {
        Objects.requireNonNull(purpose, "purpose");
        StorageObjectRecord object = requireStored(objectId);
        // 先查当前租户，跨租户时不能泄露该对象存在或用途信息。
        if (!purpose.code().equals(object.purpose())) {
            throw new ForgeException(StorageErrorCode.PURPOSE_MISMATCH);
        }
        return object;
    }

    private StorageObjectRecord requireStored(UUID objectId) {
        StorageObjectRecord object = mapper.findById(objectId);
        if (object == null || !object.stored()) {
            throw notFound(objectId);
        }
        return object;
    }

    private String prefix(UUID tenantId) {
        return tenantId + "/" + service + "/";
    }

    private static void requireFile(StoragePurpose purpose, String filename, long size) {
        Objects.requireNonNull(purpose, "purpose");
        requireName(filename, "filename");
        int dot = filename.lastIndexOf('.');
        if (dot < 0
                || !purpose.extensions().contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new ForgeException(StorageErrorCode.EXTENSION_NOT_ALLOWED);
        }
        if (size > purpose.maxSize()) {
            throw new ForgeException(StorageErrorCode.FILE_TOO_LARGE);
        }
    }

    private static void requireName(String value, String field) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_NAME_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw ForgeException.withErrorMessage(
                    FrameworkErrorCode.REQUEST_INVALID,
                    field + " must be 1 to " + MAX_NAME_LENGTH + " characters without control characters");
        }
    }

    /** 附件名按 RFC 6266 同时给出 ASCII 回退名与 UTF-8 编码名，中文文件名在浏览器中保持原样。 */
    static String contentDisposition(String filename) {
        String fallback = filename.replaceAll("[^\\x20-\\x7e]|[\"\\\\]", "_");
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A");
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static ForgeException notFound(UUID objectId) {
        return ForgeException.withErrorMessage(
                StorageErrorCode.OBJECT_NOT_FOUND, "Object " + objectId + " not found in current tenant");
    }

    private static ForgeException quotaExceeded(long quota) {
        return ForgeException.withErrorMessage(
                StorageErrorCode.QUOTA_EXCEEDED, "Storage quota of " + quota + " bytes would be exceeded");
    }
}
