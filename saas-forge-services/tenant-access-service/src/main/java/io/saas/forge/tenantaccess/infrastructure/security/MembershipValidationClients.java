package io.saas.forge.tenantaccess.infrastructure.security;

import java.util.Set;
import java.util.UUID;

/** Membership 读取仅允许部署明确指定的 Reserved Client，未配置 Remote 时保持仅 IAM。 */
public record MembershipValidationClients(Set<UUID> values) {
    public MembershipValidationClients {
        values = Set.copyOf(values);
        if (values.isEmpty() || values.stream().anyMatch(id -> id.version() != 7)) {
            throw new IllegalArgumentException("Membership Validation Client ID 必须是 UUIDv7");
        }
    }
}
