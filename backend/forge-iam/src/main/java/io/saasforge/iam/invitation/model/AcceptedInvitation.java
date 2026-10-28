package io.saasforge.iam.invitation.model;

import java.util.UUID;

/** 已接受的邀请：被邀请人登录后可进入该租户。 */
public record AcceptedInvitation(UUID tenantId, String tenantName) {}
