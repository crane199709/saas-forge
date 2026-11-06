package io.saasforge.iam.identity.model;

import java.util.UUID;

/**
 * 改密码与重设密码所需的身份投影：登录标识供 Password Policy 比对，密码哈希供校验原密码。内部投影，禁止直接作为接口响应。
 *
 * @param account 账号，由邀请建立、尚未激活的身份没有
 * @param email 邮箱，可为空
 * @param phone 手机号，可为空
 */
public record IdentityPassword(UUID identityId, String account, String email, String phone, String passwordHash) {
    @Override
    public String toString() {
        return "IdentityPassword[identityId=" + identityId + "]";
    }
}
