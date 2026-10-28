package io.saasforge.platform.tenant.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;

/** 某个服务对某个租户的初始化状态。平台表不按租户隔离，tenant_id 是普通外键列，因此在实体中声明。 */
@TableName("tenant_initialization")
public class TenantInitialization extends AuditableEntity {
    private UUID id;
    private UUID tenantId;
    private String service;
    private String status;

    // 默认更新策略跳过 null 字段；重试时须把上次的失败原因清空
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String error;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
