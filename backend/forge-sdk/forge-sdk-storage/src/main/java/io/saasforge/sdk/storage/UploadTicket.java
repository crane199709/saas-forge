package io.saasforge.sdk.storage;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 上传凭证：客户端以 {@code PUT} 把文件内容直接发送到 {@code url}，并带上 {@code headers} 中的全部请求头。请求体大小与内容类型
 * 都已签入 URL，与申请时不一致的上传会被存储拒绝。上传完成后调用 {@link ObjectStorage#completeUpload} 确认。
 *
 * @param objectId 对象 ID，确认上传与之后下载都以它定位
 * @param url 预签名的上传地址
 * @param headers 上传时必须携带的请求头；{@code Content-Length} 由客户端按请求体自动设置，不在其中
 * @param expiresAt URL 失效时间，此后未确认的上传不再占用存储容量
 */
public record UploadTicket(UUID objectId, URI url, Map<String, String> headers, OffsetDateTime expiresAt) {
    public UploadTicket {
        headers = Map.copyOf(headers);
    }
}
