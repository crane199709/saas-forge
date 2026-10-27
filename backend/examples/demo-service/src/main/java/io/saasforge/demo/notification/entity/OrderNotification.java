package io.saasforge.demo.notification.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

/** 不声明 tenant_id：消费端恢复发布方租户后，租户列由 SDK 注入。 */
@TableName("order_notifications")
public class OrderNotification extends AuditableEntity {
    private UUID id;
    private UUID orderId;
    private String title;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
