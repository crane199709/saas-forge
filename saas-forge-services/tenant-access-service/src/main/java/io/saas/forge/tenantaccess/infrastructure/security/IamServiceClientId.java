package io.saas.forge.tenantaccess.infrastructure.security;

import java.util.UUID;

/** Tenant Access 用于识别保留 IAM 调用方的部署身份。 */
public record IamServiceClientId(UUID value) {
    public IamServiceClientId {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException("IAM Service Client ID 必须是 UUIDv7");
        }
    }
}
