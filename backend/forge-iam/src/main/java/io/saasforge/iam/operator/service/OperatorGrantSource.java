package io.saasforge.iam.operator.service;

import io.saasforge.iam.operator.model.OperatorGrants;
import java.util.UUID;

/**
 * 发布明细时取 Operator 的平台角色与权限。由平台角色功能包实现：它需要经本包递增 Operator 的版本，本包只依赖这个接口，
 * 两个功能包之间不形成循环（同 {@code RoleGrantSource}）。
 */
public interface OperatorGrantSource {
    /** Operator 当前的平台角色编码、有效的平台 Permission Code 与 Data Policy。 */
    OperatorGrants grantsOf(UUID operatorId);
}
