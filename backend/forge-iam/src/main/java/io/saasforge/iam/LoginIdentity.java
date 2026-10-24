package io.saasforge.iam;

import java.util.UUID;

// 内部查询投影包含凭证，禁止直接作为接口响应。
public record LoginIdentity(
        UUID identityId,
        String passwordHash,
        String identityName,
        UUID tenantId,
        String tenantName,
        UUID membershipId,
        long version) {
    @Override
    public String toString() {
        return "LoginIdentity[identityId=" + identityId + "]";
    }
}
