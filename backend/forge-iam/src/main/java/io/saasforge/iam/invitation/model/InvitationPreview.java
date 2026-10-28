package io.saasforge.iam.invitation.model;

/**
 * 接受邀请前展示的信息。
 *
 * @param tenantName 邀请加入的租户
 * @param requiresCredentials 是否需要设置账号与密码
 */
public record InvitationPreview(String tenantName, boolean requiresCredentials) {}
