package io.saasforge.iam.operator.model;

import java.util.UUID;

// 内部查询投影包含凭证，禁止直接作为接口响应。
public record OperatorCredential(UUID operatorId, String passwordHash) {
    @Override
    public String toString() {
        return "OperatorCredential[operatorId=" + operatorId + "]";
    }
}
