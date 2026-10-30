package io.saasforge.sdk.storage;

import java.net.URI;
import java.time.OffsetDateTime;

/**
 * 下载凭证：客户端以 {@code GET} 直接从存储下载，响应以附件形式返回并带上传时的文件名。
 *
 * @param url 预签名的下载地址
 * @param expiresAt URL 失效时间
 */
public record DownloadTicket(URI url, OffsetDateTime expiresAt) {}
