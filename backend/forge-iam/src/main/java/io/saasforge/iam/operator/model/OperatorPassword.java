package io.saasforge.iam.operator.model;

import java.util.UUID;

/** 自助改密码所需的 Operator 投影：账号供 Password Policy 比对，密码哈希供校验原密码。内部投影，禁止直接作为接口响应。 */
public record OperatorPassword(UUID operatorId, String account, String passwordHash) {
    @Override
    public String toString() {
        return "OperatorPassword[operatorId=" + operatorId + "]";
    }
}
