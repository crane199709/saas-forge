package io.saasforge.iam.member.service;

import io.saasforge.iam.identity.model.ContactIdentity;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.invitation.service.InvitationService;
import io.saasforge.iam.member.model.ManagedMember;
import io.saasforge.iam.membership.model.MembershipChange;
import io.saasforge.iam.membership.model.MembershipStatus;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.membership.service.MembershipStateService;
import io.saasforge.iam.organization.service.AssignmentService;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.role.service.RoleManagementService;
import io.saasforge.iam.role.service.RoleService;
import io.saasforge.iam.tenant.service.TenantService;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant Admin 邀请、停用、恢复与移除成员，成员主动退出，Owner 转让所有者身份。
 *
 * <p>状态变更与角色分配、Owner 转让都在租户的角色写锁下进行：Owner 的判断与停用、移除之间不会插入一次转让，
 * 租户始终恰有一个 Owner。变更在事务提交后发布新版本，被停用或离开的成员下一次请求即被网关拒绝。
 *
 * <p>只改变 Membership，不提供修改 Identity 的密码、邮箱或手机号的能力：那是全局凭证，不属于任何租户。
 */
@Service
public class MemberService {
    private final MembershipService memberships;
    private final MembershipStateService membershipStates;
    private final IdentityService identities;
    private final InvitationService invitations;
    private final RoleService roles;
    private final RoleManagementService roleManagement;
    private final AssignmentService assignments;
    private final TenantService tenants;
    private final TransactionTemplate transactions;

    MemberService(
            MembershipService memberships,
            MembershipStateService membershipStates,
            IdentityService identities,
            InvitationService invitations,
            RoleService roles,
            RoleManagementService roleManagement,
            AssignmentService assignments,
            TenantService tenants,
            TransactionTemplate transactions) {
        this.memberships = memberships;
        this.membershipStates = membershipStates;
        this.identities = identities;
        this.invitations = invitations;
        this.roles = roles;
        this.roleManagement = roleManagement;
        this.assignments = assignments;
        this.tenants = tenants;
        this.transactions = transactions;
    }

    /** 一页成员，含已离开的成员；条件为 null 时不过滤。 */
    public List<ManagedMember> search(TenantActor actor, String keyword, String status, long offset, int limit) {
        requireManager(actor);
        UUID owner = roles.ownerOf(actor.tenantId()).orElse(null);
        return memberships.search(actor.tenantId(), keyword, status, offset, limit).stream()
                .map(row -> ManagedMember.of(row, row.membershipId().equals(owner)))
                .toList();
    }

    public long count(TenantActor actor, String keyword, String status) {
        requireManager(actor);
        return memberships.countSearch(actor.tenantId(), keyword, status);
    }

    /**
     * 按邮箱或手机号邀请成员，复用开通的邀请机制：已有 Identity 收到加入邀请，没有的建立待激活 Identity 并收到注册邀请。
     * 已离开的成员回到 invited；仍为 invited 的再发一封邀请。
     *
     * <p>预设角色与部门分别要求 {@code iam:role:manage}、{@code iam:org:manage}，在建立 Membership 之后按各自的规则写入，
     * 最后才发出邀请。预设失败时 Membership 已是 invited 但邀请未发出，修正后再次邀请即可补齐。
     *
     * @param roleIds 预设角色，不含 Owner
     * @param primaryDepartmentId 预设主部门，可为 null
     */
    public MembershipStatus invite(
            TenantActor actor, String contact, Collection<UUID> roleIds, UUID primaryDepartmentId) {
        requireManager(actor);
        if (!roleIds.isEmpty()) roles.requirePermission(actor.membershipId(), IamPermissions.ROLE_MANAGE);
        if (primaryDepartmentId != null) roles.requirePermission(actor.membershipId(), IamPermissions.ORG_MANAGE);
        ContactIdentity identity = identities.findOrCreatePending(contact);
        MembershipStatus membership = transactions.execute(status -> {
            roles.lockTenant(actor.tenantId());
            MembershipStatus invited = memberships.invite(actor.tenantId(), identity.identityId(), actor.identityId());
            if (!invited.invited()) throw new ForgeException(MemberErrorCode.ALREADY_MEMBER);
            return invited;
        });
        if (!roleIds.isEmpty()) roleManagement.assign(actor, membership.membershipId(), roleIds);
        if (primaryDepartmentId != null) {
            assignments.assignPrimaryDepartment(actor, membership.membershipId(), primaryDepartmentId);
        }
        invitations.invite(contact, tenants.name(actor.tenantId()), membership, identity.pending());
        return membership;
    }

    /** 停用正常的成员，立即生效；可恢复。 */
    public void suspend(TenantActor actor, UUID membershipId) {
        requireManager(actor);
        change(
                actor,
                membershipId,
                true,
                () -> memberships.transition(
                        actor.tenantId(),
                        membershipId,
                        MembershipStatus.ACTIVE,
                        MembershipStatus.SUSPENDED,
                        actor.identityId()));
    }

    /** 恢复停用的成员；停用前签发的 Token 仍然无效，成员重新登录或刷新后进入。 */
    public void resume(TenantActor actor, UUID membershipId) {
        requireManager(actor);
        change(
                actor,
                membershipId,
                false,
                () -> memberships.transition(
                        actor.tenantId(),
                        membershipId,
                        MembershipStatus.SUSPENDED,
                        MembershipStatus.ACTIVE,
                        actor.identityId()));
    }

    /** 移除成员（含撤回尚未接受的邀请）：成员变为已离开，角色与组织分配一并清除。 */
    public void remove(TenantActor actor, UUID membershipId) {
        requireManager(actor);
        leave(actor, membershipId);
    }

    /** 成员主动退出当前租户；Owner 须先转让。 */
    public void leave(TenantActor actor) {
        leave(actor, actor.membershipId());
    }

    /**
     * Owner 把所有者身份转让给本租户另一个正常状态的成员，原 Owner 降为 Tenant Admin。双方的权限随之变化，旧 Token 立即失效。
     */
    public void transferOwner(TenantActor actor, UUID targetId) {
        if (targetId.equals(actor.membershipId())) {
            throw new ForgeException(MemberErrorCode.INVALID_TRANSFER_TARGET);
        }
        transactions.executeWithoutResult(status -> {
            roles.lockTenant(actor.tenantId());
            if (!memberships.lockActive(actor.tenantId(), targetId)) {
                throw new ForgeException(MemberErrorCode.INVALID_TRANSFER_TARGET);
            }
            if (!roles.transferOwner(actor.tenantId(), actor.membershipId(), targetId)) {
                throw new ForgeException(MemberErrorCode.NOT_OWNER);
            }
        });
        membershipStates.incrementVersion(actor.membershipId());
        membershipStates.incrementVersion(targetId);
    }

    private void leave(TenantActor actor, UUID membershipId) {
        change(actor, membershipId, true, () -> {
            Optional<MembershipChange> change = memberships.leave(actor.tenantId(), membershipId, actor.identityId());
            change.ifPresent(ignored -> {
                roles.revokeAll(membershipId);
                assignments.clear(actor.tenantId(), membershipId);
            });
            return change;
        });
    }

    // 角色写锁先于 Membership 行锁取得，与角色分配的加锁顺序一致；版本只能在提交后发布，理由同 RoleManagementService。
    private void change(
            TenantActor actor, UUID membershipId, boolean protectOwner, Supplier<Optional<MembershipChange>> update) {
        MembershipChange change = transactions.execute(status -> {
            roles.lockTenant(actor.tenantId());
            String current = memberships
                    .status(actor.tenantId(), membershipId)
                    .orElseThrow(() -> new ForgeException(MemberErrorCode.NOT_FOUND));
            if (protectOwner
                    && roles.ownerOf(actor.tenantId())
                            .filter(membershipId::equals)
                            .isPresent()) {
                throw new ForgeException(MemberErrorCode.OWNER_PROTECTED);
            }
            return update.get()
                    .orElseThrow(() -> ForgeException.withErrorMessage(
                            MemberErrorCode.ILLEGAL_STATUS, "Membership is " + current));
        });
        membershipStates.publish(change.tenantId(), membershipId, change.version());
    }

    private void requireManager(TenantActor actor) {
        roles.requirePermission(actor.membershipId(), IamPermissions.MEMBER_MANAGE);
    }
}
