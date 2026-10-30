package io.saasforge.sdk.storage;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkException;
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
 * 确认，对象此后才可下载。已用容量包含尚未过期的上传申请，并发申请不会合计超出 Quota；{@link #delete} 删除对象后释放容量。
 *
 * <p>所有方法都要求已绑定租户上下文。
 */
public class ObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    /** S3 单次 PUT 的大小上限。 */
    static final long MAX_OBJECT_SIZE = 5L * 1024 * 1024 * 1024;

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

    ObjectStorage(
            S3Client s3,
            S3Presigner presigner,
            StorageObjectMapper mapper,
            TransactionTemplate transaction,
            StorageProperties properties,
            String service,
            Clock clock) {
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
    }

    /**
     * 申请上传：校验存储容量 Quota 后登记对象并签发上传 URL。
     *
     * @param filename 文件名，只作为下载时的附件名，不进入对象 key；不超过 255 个字符
     * @param contentType 内容类型，为 null 或空白时为 {@code application/octet-stream}
     * @param size 文件大小（字节），上传的内容须与之一致
     * @return 上传凭证
     * @throws ForgeException 参数非法时为 {@code request.invalid}；上传后将超出 Quota 时为 {@link StorageErrorCode#QUOTA_EXCEEDED}
     */
    public UploadTicket prepareUpload(String filename, String contentType, long size) {
        requireName(filename, "filename");
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
            if (quota != null && mapper.usedBytes(now, null) + size > quota) {
                throw quotaExceeded(quota);
            }
            mapper.insertPending(id, key, filename, type, size, expiresAt);
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
     *     {@link StorageErrorCode#UPLOAD_INCOMPLETE}；超出 Quota 时为 {@link StorageErrorCode#QUOTA_EXCEEDED}，对象随之删除
     */
    public StoredObject completeUpload(UUID objectId) {
        StorageObjectRecord object = mapper.findById(objectId);
        if (object == null) {
            throw notFound(objectId);
        }
        if (object.stored()) {
            return object.toStoredObject();
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
            if (quota != null && mapper.usedBytes(now, objectId) + object.size() > quota) {
                mapper.delete(objectId);
                return quota;
            }
            mapper.markStored(objectId);
            return null;
        });
        if (rejectedQuota != null) {
            // 登记已随事务删除；对象删除失败只会在存储中留下不再计入容量、也无法访问的孤儿
            s3.deleteObject(request -> request.bucket(properties.bucket()).key(object.objectKey()));
            throw quotaExceeded(rejectedQuota);
        }
        return get(objectId);
    }

    /**
     * 读取已确认上传的对象。
     *
     * @param objectId 对象 ID
     * @return 对象
     * @throws ForgeException 对象不存在、未确认上传或属于其他租户时为 {@link StorageErrorCode#OBJECT_NOT_FOUND}
     */
    public StoredObject get(UUID objectId) {
        return requireStored(objectId).toStoredObject();
    }

    /**
     * 签发下载 URL。
     *
     * @param objectId 对象 ID
     * @return 下载凭证
     * @throws ForgeException 对象不存在、未确认上传或属于其他租户时为 {@link StorageErrorCode#OBJECT_NOT_FOUND}
     */
    public DownloadTicket prepareDownload(UUID objectId) {
        StorageObjectRecord object = requireStored(objectId);
        // 登记已按租户隔离；再核对一次前缀，签名的 key 绝不会落在当前租户之外
        if (!object.objectKey().startsWith(prefix(CurrentTenant.require()))) {
            throw notFound(objectId);
        }
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(properties.downloadUrlTtl());
        PresignedGetObjectRequest presigned =
                presigner.presignGetObject(request -> request.signatureDuration(properties.downloadUrlTtl())
                        .getObjectRequest(get -> get.bucket(properties.bucket())
                                .key(object.objectKey())
                                .responseContentType(object.contentType())
                                .responseContentDisposition(contentDisposition(object.filename()))));
        return new DownloadTicket(URI.create(presigned.url().toString()), expiresAt);
    }

    /**
     * 删除对象：删除登记，并在事务提交后删除存储中的文件，释放其占用的存储容量。已确认与尚未确认上传的对象都可删除。
     *
     * <p>调用方在事务中时，登记随调用方事务一起提交或回滚，文件只在提交后删除，回滚不会留下没有文件的登记。文件删除失败只记日志：
     * 登记已不存在，文件不再计入容量也无法访问。
     *
     * @param objectId 对象 ID
     * @throws ForgeException 对象不存在或属于其他租户时为 {@link StorageErrorCode#OBJECT_NOT_FOUND}
     */
    public void delete(UUID objectId) {
        StorageObjectRecord object = mapper.findById(objectId);
        if (object == null) {
            throw notFound(objectId);
        }
        transaction.executeWithoutResult(status -> {
            mapper.delete(objectId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        s3.deleteObject(
                                request -> request.bucket(properties.bucket()).key(object.objectKey()));
                    } catch (SdkException e) {
                        log.warn("Failed to delete object {} from storage, left as orphan", object.objectKey(), e);
                    }
                }
            });
        });
    }

    /**
     * 当前租户在本服务中的存储用量。
     *
     * @return 已用容量与 Quota
     */
    public StorageUsage usage() {
        return new StorageUsage(mapper.usedBytes(OffsetDateTime.now(clock), null), mapper.quota());
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
