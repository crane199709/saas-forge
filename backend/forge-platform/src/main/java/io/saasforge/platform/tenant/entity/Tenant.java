package io.saasforge.platform.tenant.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.time.OffsetDateTime;
import java.util.UUID;

@TableName("tenant")
public class Tenant extends AuditableEntity {
    private UUID id;
    private String name;
    private String status;
    private String adminContact;
    private Long statusVersion;
    private OffsetDateTime expiresAt;
    private OffsetDateTime expiryRemindedAt;
    private String statusBeforeDeletion;
    private OffsetDateTime purgeAt;
    private OffsetDateTime purgedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAdminContact() {
        return adminContact;
    }

    public void setAdminContact(String adminContact) {
        this.adminContact = adminContact;
    }

    public Long getStatusVersion() {
        return statusVersion;
    }

    public void setStatusVersion(Long statusVersion) {
        this.statusVersion = statusVersion;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public OffsetDateTime getExpiryRemindedAt() {
        return expiryRemindedAt;
    }

    public void setExpiryRemindedAt(OffsetDateTime expiryRemindedAt) {
        this.expiryRemindedAt = expiryRemindedAt;
    }

    public String getStatusBeforeDeletion() {
        return statusBeforeDeletion;
    }

    public void setStatusBeforeDeletion(String statusBeforeDeletion) {
        this.statusBeforeDeletion = statusBeforeDeletion;
    }

    public OffsetDateTime getPurgeAt() {
        return purgeAt;
    }

    public void setPurgeAt(OffsetDateTime purgeAt) {
        this.purgeAt = purgeAt;
    }

    public OffsetDateTime getPurgedAt() {
        return purgedAt;
    }

    public void setPurgedAt(OffsetDateTime purgedAt) {
        this.purgedAt = purgedAt;
    }
}
