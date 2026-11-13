package io.saasforge.demo.attachment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.demo.attachment.entity.OrderAttachment;
import io.saasforge.demo.attachment.mapper.OrderAttachmentMapper;
import io.saasforge.demo.order.service.OrderService;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.storage.DownloadTicket;
import io.saasforge.sdk.storage.ObjectStorage;
import io.saasforge.sdk.storage.StoragePurpose;
import io.saasforge.sdk.storage.StoredObject;
import io.saasforge.sdk.storage.UploadTicket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示订单附件：文件经 SDK 签发的预签名 URL 由客户端直传对象存储，服务只登记归属。业务代码不接触对象 key，
 * 租户前缀、跨租户隔离与存储容量 Quota 都由 SDK 负责。
 *
 * <p>每个操作先读取订单：订单不存在或超出成员的数据范围时与订单接口一样返回 {@code order.notFound}。
 */
@Service
public class OrderAttachmentService {
    private static final StoragePurpose ATTACHMENT = new StoragePurpose(
            "order-attachment",
            Set.of("txt", "pdf", "xlsx", "png", "jpg", "jpeg"),
            20L * 1024 * 1024,
            Duration.ofMinutes(5),
            30);

    private final OrderAttachmentMapper attachmentMapper;
    private final OrderService orderService;
    private final ObjectStorage storage;

    public OrderAttachmentService(
            OrderAttachmentMapper attachmentMapper, OrderService orderService, ObjectStorage storage) {
        this.attachmentMapper = attachmentMapper;
        this.orderService = orderService;
        this.storage = storage;
    }

    public UploadTicket prepareUpload(UUID orderId, String filename, String contentType, long size) {
        orderService.get(orderId);
        return storage.prepareUpload(ATTACHMENT, filename, contentType, size);
    }

    /** 确认上传不放在事务中：SDK 因超出 Quota 标记文件失效时须独立提交，不能随调用方回滚。 */
    public OrderAttachment attach(UUID orderId, UUID objectId) {
        orderService.get(orderId);
        storage.completeUpload(objectId);
        StoredObject object = storage.get(ATTACHMENT, objectId);
        boolean attached = attachmentMapper.exists(
                new LambdaQueryWrapper<OrderAttachment>().eq(OrderAttachment::getObjectId, objectId));
        if (attached) {
            throw ForgeException.withErrorMessage(
                    AttachmentErrorCode.ALREADY_ATTACHED, "Object " + objectId + " is already attached");
        }
        var attachment = new OrderAttachment();
        attachment.setOrderId(orderId);
        attachment.setObjectId(object.id());
        attachment.setFilename(object.filename());
        attachment.setContentType(object.contentType());
        attachment.setSize(object.size());
        attachmentMapper.insert(attachment);
        return attachmentMapper.selectById(attachment.getId());
    }

    public List<OrderAttachment> list(UUID orderId) {
        orderService.get(orderId);
        return attachmentMapper.selectList(new LambdaQueryWrapper<OrderAttachment>()
                .eq(OrderAttachment::getOrderId, orderId)
                .orderByAsc(OrderAttachment::getCreatedAt));
    }

    public DownloadTicket prepareDownload(UUID orderId, UUID attachmentId) {
        return storage.prepareDownload(ATTACHMENT, find(orderId, attachmentId).getObjectId());
    }

    /** 附件逻辑删除与文件失效同一事务提交；逻辑删除行仍保护文件，供业务恢复使用。 */
    @Transactional
    public void delete(UUID orderId, UUID attachmentId) {
        OrderAttachment attachment = find(orderId, attachmentId);
        attachmentMapper.deleteById(attachment);
        storage.delete(attachment.getObjectId());
    }

    private OrderAttachment find(UUID orderId, UUID attachmentId) {
        orderService.get(orderId);
        OrderAttachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !attachment.getOrderId().equals(orderId)) {
            throw ForgeException.withErrorMessage(
                    AttachmentErrorCode.NOT_FOUND, "Attachment " + attachmentId + " not found in order " + orderId);
        }
        return attachment;
    }
}
