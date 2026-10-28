package io.saasforge.iam.tenant.service;

import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.verification.service.ContactSender;
import io.saasforge.iam.verification.service.ExpiryReminderMessage;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantExpiryApproached;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 租户试用或订购即将到期时，经邮件/短信 SPI 提醒 Owner。
 *
 * <p>发送失败时抛出，消息重投后再次发送；同一事件的重复投递已由 SDK 去重，但重投前已发出的部分不会撤回，Owner 可能多收到一次。
 */
@Service
class TenantExpiryReminderHandler implements EventHandler<TenantExpiryApproached> {
    private static final Logger log = LoggerFactory.getLogger(TenantExpiryReminderHandler.class);

    private final RoleService roles;
    private final ContactSender sender;

    TenantExpiryReminderHandler(RoleService roles, ContactSender sender) {
        this.roles = roles;
        this.sender = sender;
    }

    @Override
    public String topic() {
        return TenantCreated.TOPIC;
    }

    @Override
    public Class<TenantExpiryApproached> eventType() {
        return TenantExpiryApproached.class;
    }

    @Override
    public void handle(TenantExpiryApproached event) {
        List<String> targets = roles.ownerContacts(event.tenantId());
        if (targets.isEmpty()) {
            log.warn("Tenant {} has no owner to remind of expiry at {}", event.tenantId(), event.expiresAt());
            return;
        }
        var message = new ExpiryReminderMessage(event.name(), "trial".equals(event.status()), event.expiresAt());
        targets.forEach(target -> sender.send(target, message));
    }
}
