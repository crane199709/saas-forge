package io.saasforge.iam.operatoradmin.model;

import io.saasforge.iam.operator.model.OperatorRow;
import java.util.List;
import java.util.UUID;

/**
 * 管理界面中的一个 Operator 及其平台角色。
 *
 * @param roleIds 持有的平台角色
 */
public record ManagedOperator(OperatorRow operator, List<UUID> roleIds) {}
