package io.saasforge.sdk.storage;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 已确认上传的对象。不含对象 key：key 只在 SDK 内部使用，业务代码以对象 ID 访问。
 *
 * @param id 对象 ID
 * @param filename 上传时的文件名，下载时作为附件名
 * @param contentType 内容类型
 * @param size 大小（字节）
 * @param storedAt 确认上传的时间
 */
public record StoredObject(UUID id, String filename, String contentType, long size, OffsetDateTime storedAt) {}
