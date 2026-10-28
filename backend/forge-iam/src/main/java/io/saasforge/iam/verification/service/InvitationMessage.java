package io.saasforge.iam.verification.service;

import java.time.Duration;

/**
 * 加入租户的邀请。
 *
 * @param tenantName 邀请加入的租户名称
 * @param acceptUrl 接受邀请的链接，含一次性邀请令牌
 * @param ttl 邀请有效期
 * @param requiresCredentials 被邀请人尚无可登录的 Identity，接受时需设置账号与密码
 */
public record InvitationMessage(String tenantName, String acceptUrl, Duration ttl, boolean requiresCredentials)
        implements ContactMessage {
    @Override
    public String toString() {
        return "InvitationMessage[tenantName=" + tenantName + ", ttl=" + ttl + "]";
    }
}
