package io.saasforge.demo.attachment.controller;

import io.saasforge.demo.attachment.entity.OrderAttachment;
import io.saasforge.demo.attachment.service.OrderAttachmentService;
import io.saasforge.sdk.audit.AuditLog;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.saasforge.sdk.storage.DownloadTicket;
import io.saasforge.sdk.storage.UploadTicket;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "订单附件")
@RestController
@RequestMapping("/orders/{orderId}/attachments")
class OrderAttachmentController {
    private final OrderAttachmentService attachmentService;

    OrderAttachmentController(OrderAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Operation(summary = "申请上传附件：校验存储容量后返回预签名上传地址，客户端以 PUT 直传文件")
    @PostMapping("/uploads")
    @RequiresPermission("order:update")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UploadResponse> prepareUpload(@PathVariable UUID orderId, @RequestBody UploadRequest request) {
        UploadTicket ticket = attachmentService.prepareUpload(
                orderId, request.filename(), request.contentType(), request.size() == null ? 0 : request.size());
        return ApiResponse.created(
                new UploadResponse(ticket.objectId(), ticket.url(), ticket.headers(), ticket.expiresAt()));
    }

    @Operation(summary = "上传完成后登记为订单附件")
    @PostMapping
    @RequiresPermission("order:update")
    @AuditLog(action = "order.attach", resourceType = "order", resourceId = "#orderId")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AttachmentResponse> attach(@PathVariable UUID orderId, @RequestBody AttachRequest request) {
        return ApiResponse.created(AttachmentResponse.from(attachmentService.attach(orderId, request.objectId())));
    }

    @Operation(summary = "列出订单的附件")
    @GetMapping
    @RequiresPermission("order:read")
    ApiResponse<List<AttachmentResponse>> list(@PathVariable UUID orderId) {
        return ApiResponse.ok(attachmentService.list(orderId).stream()
                .map(AttachmentResponse::from)
                .toList());
    }

    @Operation(summary = "返回附件的预签名下载地址")
    @GetMapping("/{attachmentId}/download")
    @RequiresPermission("order:read")
    ApiResponse<DownloadResponse> download(@PathVariable UUID orderId, @PathVariable UUID attachmentId) {
        DownloadTicket ticket = attachmentService.prepareDownload(orderId, attachmentId);
        return ApiResponse.ok(new DownloadResponse(ticket.url(), ticket.expiresAt()));
    }

    @Operation(summary = "删除附件及其文件，释放占用的存储容量")
    @DeleteMapping("/{attachmentId}")
    @RequiresPermission("order:update")
    @AuditLog(action = "order.detach", resourceType = "order", resourceId = "#orderId")
    ApiResponse<Void> delete(@PathVariable UUID orderId, @PathVariable UUID attachmentId) {
        attachmentService.delete(orderId, attachmentId);
        return ApiResponse.ok();
    }

    /** @param size 文件大小（字节） */
    record UploadRequest(String filename, String contentType, Long size) {}

    /** @param headers 上传时必须携带的请求头 */
    record UploadResponse(UUID objectId, URI url, Map<String, String> headers, OffsetDateTime expiresAt) {}

    record AttachRequest(UUID objectId) {}

    record AttachmentResponse(UUID id, String filename, String contentType, long size, OffsetDateTime createdAt) {
        static AttachmentResponse from(OrderAttachment attachment) {
            return new AttachmentResponse(
                    attachment.getId(),
                    attachment.getFilename(),
                    attachment.getContentType(),
                    attachment.getSize(),
                    attachment.getCreatedAt());
        }
    }

    record DownloadResponse(URI url, OffsetDateTime expiresAt) {}
}
