package io.saasforge.iam.identity.model;

import java.util.UUID;

// 内部查询投影包含凭证，禁止直接作为接口响应。
public record LoginCredential(UUID identityId, String passwordHash) {
    @Override
    public String toString() {
        return "LoginCredential[identityId=" + identityId + "]";
    }
}
