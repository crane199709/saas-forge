package io.saasforge.iam.platformrole.model;

import java.util.UUID;

/** Operator 持有的一个平台角色。 */
public record OperatorRoleRow(UUID operatorId, UUID roleId) {}
