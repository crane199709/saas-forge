package io.saasforge.iam.role.model;

import java.util.UUID;

/** 角色变更影响预览中列出的一个成员。 */
public record AffectedMember(UUID membershipId, String account, String displayName) {}
