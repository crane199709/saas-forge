package io.saasforge.iam.membership.service;

import io.saasforge.iam.membership.model.RoleGrants;
import java.util.UUID;

/**
 * 发布明细时取 Membership 的角色与权限。由角色功能包实现：角色功能包需要经本包递增版本，
 * 本包只依赖这个接口，两个功能包之间不形成循环。
 */
public interface RoleGrantSource {
    /** Membership 当前的角色编码与有效 Permission Code。 */
    RoleGrants grantsOf(UUID membershipId);
}
