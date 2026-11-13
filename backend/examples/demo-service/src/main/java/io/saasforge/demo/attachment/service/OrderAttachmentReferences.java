package io.saasforge.demo.attachment.service;

import io.saasforge.demo.attachment.mapper.OrderAttachmentMapper;
import io.saasforge.sdk.storage.FileReferenceChecker;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 只负责订单附件用途；其他用途必须由所属模块声明检查器。 */
@Component
public final class OrderAttachmentReferences implements FileReferenceChecker {
    private final OrderAttachmentMapper mapper;

    public OrderAttachmentReferences(OrderAttachmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<String> purposes() {
        return Set.of("order-attachment");
    }

    @Override
    public Set<UUID> referencedObjects(String purpose, List<UUID> candidates) {
        return mapper.referencedObjects(candidates);
    }
}
