package io.saasforge.iam.membership.service;

import io.saasforge.sdk.core.SubjectAttributes;
import java.util.UUID;

/**
 * 发布明细时取 Membership 的主体属性。由组织功能包实现：组织功能包需要经本包递增版本，
 * 本包只依赖这个接口，两个功能包之间不形成循环。
 */
public interface SubjectAttributeSource {
    /** Membership 当前的部门路径、岗位与扩展属性值。 */
    SubjectAttributes subjectOf(UUID tenantId, UUID membershipId);
}
