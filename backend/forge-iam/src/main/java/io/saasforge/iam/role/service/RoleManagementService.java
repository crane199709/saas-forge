package io.saasforge.iam.role.service;

import io.saasforge.iam.membership.model.Member;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.membership.service.MembershipNotFoundException;
import io.saasforge.iam.membership.service.MembershipService;
import io.saasforge.iam.membership.service.MembershipStateService;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.model.ServiceDataResource;
import io.saasforge.iam.permission.service.IamPermissions;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.role.mapper.RoleDataPolicyMapper;
import io.saasforge.iam.role.mapper.RoleMapper;
import io.saasforge.iam.role.mapper.RolePermissionMapper;
import io.saasforge.iam.role.model.AffectedMember;
import io.saasforge.iam.role.model.ConditionOptions;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.DataResourceOption;
import io.saasforge.iam.role.model.MemberRoles;
import io.saasforge.iam.role.model.MembershipCodeRow;
import io.saasforge.iam.role.model.MembershipRoleRow;
import io.saasforge.iam.role.model.PolicyPreview;
import io.saasforge.iam.role.model.RoleDataPolicyImpactResponse;
import io.saasforge.iam.role.model.RoleDataPolicyRow;
import io.saasforge.iam.role.model.RoleDetail;
import io.saasforge.iam.role.model.RolePermissionImpactResponse;
import io.saasforge.iam.role.model.RolePermissionRow;
import io.saasforge.iam.role.model.RoleRow;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tenant Admin 维护自定义角色、角色授权、角色上的 Data Policy 与成员的角色分配。变更影响到的成员在事务提交后递增 version，
 * 旧 Token 立即失效，刷新后按新权限签发。每次变更提交后记录一条权限变更 Audit Event。
 *
 * <p>与 {@link RoleService} 分开：发布明细的 {@link MembershipStateService} 经 {@link RoleService} 读取授权，
 * 本服务又要经它递增版本，合在一起会形成 Bean 循环依赖。
 */
@Service
public class RoleManagementService {
    private static final int IMPACT_MEMBER_LIMIT = 20;

    private final RoleMapper roles;
    private final RolePermissionMapper grants;
    private final RoleDataPolicyMapper dataPolicies;
    private final RoleService roleService;
    private final PermissionService permissions;
    private final MembershipService memberships;
    private final MembershipStateService membershipStates;
    private final DataConditionService conditions;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final AuditRecorder audits;

    RoleManagementService(
            RoleMapper roles,
            RolePermissionMapper grants,
            RoleDataPolicyMapper dataPolicies,
            RoleService roleService,
            PermissionService permissions,
            MembershipService memberships,
            MembershipStateService membershipStates,
            DataConditionService conditions,
            TransactionTemplate transactions,
            JsonMapper json,
            AuditRecorder audits) {
        this.roles = roles;
        this.grants = grants;
        this.dataPolicies = dataPolicies;
        this.roleService = roleService;
        this.permissions = permissions;
        this.memberships = memberships;
        this.membershipStates = membershipStates;
        this.conditions = conditions;
        this.transactions = transactions;
        this.json = json;
        this.audits = audits;
    }

    /** 租户的全部角色及其有效授权与 Data Policy，含开通时按业务服务模板预置的角色。 */
    public List<RoleDetail> list(TenantActor actor) {
        requireManager(actor);
        // 超出 Plan 的授权保留但不展示：授权界面只能在 Plan 内选择，整体替换时也不会撤销它们。
        Set<String> grantable = grantableCodes(actor.tenantId());
        Map<UUID, List<String>> codesOf = new HashMap<>();
        for (RolePermissionRow row : grants.listByTenant(actor.tenantId())) {
            if (!grantable.contains(row.code())) continue;
            codesOf.computeIfAbsent(row.roleId(), key -> new ArrayList<>()).add(row.code());
        }
        Map<UUID, List<DataPolicy>> policiesOf = new HashMap<>();
        for (RoleDataPolicyRow row : dataPolicies.listByTenant(actor.tenantId())) {
            policiesOf
                    .computeIfAbsent(row.roleId(), key -> new ArrayList<>())
                    .add(new DataPolicy(row.resource(), row.scope(), row.condition()));
        }
        return roles.list(actor.tenantId()).stream()
                .map(row -> RoleDetail.of(
                        row, codesOf.getOrDefault(row.id(), List.of()), policiesOf.getOrDefault(row.id(), List.of())))
                .toList();
    }

    /** 各业务服务当前声明的 Data Resource 及其可用的 Data Scope 预设与资源属性，供角色编辑选择。 */
    public List<DataResourceOption> dataResources(TenantActor actor) {
        requireManager(actor);
        return conditions.dataResources();
    }

    /** 自定义条件可引用的主体与环境属性，以及部门、岗位取值。 */
    public ConditionOptions conditionOptions(TenantActor actor) {
        requireManager(actor);
        return conditions.options(actor.tenantId());
    }

    /** 预览策略对本租户某个成员在样例数据上的命中情况，不保存任何内容。 */
    public PolicyPreview preview(
            TenantActor actor, DataPolicy policy, UUID membershipId, List<Map<String, Object>> samples) {
        requireManager(actor);
        return conditions.preview(actor.tenantId(), policy, membershipId, samples);
    }

    /**
     * 整体替换自定义角色在当前声明的 Data Resource 上的 Data Policy，每个资源至多一条；未配置的资源对持有者默认不可见。
     * 已从清单下线的资源上的策略保留不动。持有该角色的成员随即按新范围处理。
     */
    public void replaceDataPolicies(TenantActor actor, UUID id, Collection<DataPolicy> policies) {
        requireManager(actor);
        Set<String> declared = declaredResources();
        List<DataPolicy> requested = conditions.requireValid(actor.tenantId(), policies);
        List<UUID> affected = transactions.execute(status -> {
            roles.lockTenant(actor.tenantId());
            requireCustom(actor, id);
            List<DataPolicy> current = dataPolicies.byRole(id).stream()
                    .filter(policy -> declared.contains(policy.resource()))
                    .toList();
            if (same(current, requested)) return List.<UUID>of();
            if (!declared.isEmpty()) dataPolicies.deleteResources(id, declared);
            if (!requested.isEmpty()) dataPolicies.insert(id, requested);
            return roles.holders(id);
        });
        publish(affected);
        audits.record(audit(actor, "role.dataPoliciesReplace", "role", id)
                .detail("dataPolicies", requested)
                .build());
    }

    /** 可授予的 Permission Code：只含有效且在租户 Plan 范围内的，已下线或超出 Plan 的不出现。 */
    public List<PermissionRow> grantable(TenantActor actor) {
        requireManager(actor);
        return roleService.grantablePermissions(actor.tenantId());
    }

    /** 新建自定义角色，权限码授权与 Data Policy 同一事务写入；新角色还没有持有者，不需要递增版本。 */
    public RoleDetail create(
            TenantActor actor, String code, String name, Collection<String> codes, Collection<DataPolicy> policies) {
        requireManager(actor);
        Set<String> granted = requireGrantable(actor.tenantId(), codes);
        List<DataPolicy> scoped = conditions.requireValid(actor.tenantId(), policies);
        // 保留编码由数据库 CHECK 兜底，这里先拒绝，避免以 500 暴露约束错误。
        if (RoleService.ALL_PERMISSIONS.contains(code)) throw new ForgeException(RoleErrorCode.CODE_TAKEN, code);
        UUID id = UuidV7.generate();
        transactions.executeWithoutResult(status -> {
            if (roles.insertCustom(actor.tenantId(), id, code, name, actor.identityId()) == 0) {
                throw new ForgeException(RoleErrorCode.CODE_TAKEN, code);
            }
            if (!granted.isEmpty()) grants.grant(id, granted);
            if (!scoped.isEmpty()) dataPolicies.insert(id, scoped);
        });
        audits.record(audit(actor, "role.create", "role", id)
                .detail("code", code)
                .detail("name", name)
                .detail("permissions", sorted(granted))
                .detail("dataPolicies", scoped)
                .build());
        return RoleDetail.of(new RoleRow(id, code, name, false, false, 0), sorted(granted), scoped);
    }

    public void rename(TenantActor actor, UUID id, String name) {
        requireManager(actor);
        requireCustom(actor, id);
        if (roles.rename(actor.tenantId(), id, name, actor.identityId()) == 0) {
            throw new ForgeException(RoleErrorCode.NOT_FOUND);
        }
        audits.record(
                audit(actor, "role.rename", "role", id).detail("name", name).build());
    }

    /**
     * 整体替换角色在可授予范围内的授权；已下线或超出 Plan 的权限码上的授权保留，权限码恢复或租户升级后随之恢复。
     * 持有该角色的成员随即失去或获得权限。
     */
    public void replacePermissions(TenantActor actor, UUID id, Collection<String> codes) {
        requireManager(actor);
        Set<String> granted = requireGrantable(actor.tenantId(), codes);
        Set<String> revoked = new HashSet<>(grantableCodes(actor.tenantId()));
        revoked.removeAll(granted);
        List<UUID> affected = transactions.execute(status -> {
            roles.lockTenant(actor.tenantId());
            requireCustom(actor, id);
            int changed = revoked.isEmpty() ? 0 : grants.revoke(id, revoked);
            if (!granted.isEmpty()) changed += grants.grant(id, granted);
            return changed == 0 ? List.<UUID>of() : roles.holders(id);
        });
        publish(affected);
        audits.record(audit(actor, "role.permissionsReplace", "role", id)
                .detail("permissions", sorted(granted))
                .build());
    }

    /**
     * 预览 {@link #replacePermissions} 的净影响，校验与修改相同，不写任何数据。成员经其他角色仍持有，或本来就超出 Plan 而未生效的
     * 权限码都不算失去。只统计 active 的 Membership；不取租户锁，预览只作提示，与并发修改之间不要求一致。
     */
    public RolePermissionImpactResponse previewPermissions(TenantActor actor, UUID id, Collection<String> codes) {
        requireManager(actor);
        Set<String> requested = requireGrantable(actor.tenantId(), codes);
        requireCustom(actor, id);
        Set<String> grantable = grantableCodes(actor.tenantId());
        Set<String> current = new HashSet<>();
        for (RolePermissionRow row : grants.listByTenant(actor.tenantId())) {
            if (row.roleId().equals(id)) current.add(row.code());
        }
        Map<UUID, Set<String>> otherGrants = new HashMap<>();
        for (MembershipCodeRow row : grants.grantedByOtherRoles(id)) {
            otherGrants
                    .computeIfAbsent(row.membershipId(), key -> new HashSet<>())
                    .add(row.code());
        }
        List<UUID> affected = new ArrayList<>();
        Set<String> lost = new TreeSet<>();
        activeHolderRoles(actor.tenantId(), id).forEach((membershipId, roleCodes) -> {
            Set<String> others = otherGrants.getOrDefault(membershipId, Set.of());
            Set<String> lostByMember =
                    new HashSet<>(RoleService.effectivePermissions(roleCodes, union(others, current), grantable));
            lostByMember.removeAll(RoleService.effectivePermissions(roleCodes, union(others, requested), grantable));
            if (lostByMember.isEmpty()) return;
            affected.add(membershipId);
            lost.addAll(lostByMember);
        });
        return new RolePermissionImpactResponse(
                affected.size(), firstMembers(actor.tenantId(), affected), List.copyOf(lost));
    }

    /** 预览 {@link #replaceDataPolicies} 影响的成员，校验与修改相同，不写任何数据。只列出持有该角色的 active Membership，不判断具体数据行的变化。 */
    public RoleDataPolicyImpactResponse previewDataPolicies(
            TenantActor actor, UUID id, Collection<DataPolicy> policies) {
        requireManager(actor);
        conditions.requireValid(actor.tenantId(), policies);
        requireCustom(actor, id);
        List<UUID> holders = List.copyOf(activeHolderRoles(actor.tenantId(), id).keySet());
        return new RoleDataPolicyImpactResponse(holders.size(), firstMembers(actor.tenantId(), holders));
    }

    // 按 Membership 标识保持查询顺序，影响预览的「前 20 名」因此是确定的。
    private Map<UUID, List<String>> activeHolderRoles(UUID tenantId, UUID roleId) {
        Map<UUID, List<String>> holders = new LinkedHashMap<>();
        for (MembershipCodeRow row : roles.activeHolderRoleCodes(tenantId, roleId)) {
            holders.computeIfAbsent(row.membershipId(), key -> new ArrayList<>())
                    .add(row.code());
        }
        return holders;
    }

    private List<AffectedMember> firstMembers(UUID tenantId, List<UUID> membershipIds) {
        return memberships
                .findAll(tenantId, membershipIds.subList(0, Math.min(IMPACT_MEMBER_LIMIT, membershipIds.size())))
                .stream()
                .map(member -> new AffectedMember(member.membershipId(), member.account(), member.displayName()))
                .toList();
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> result = new HashSet<>(first);
        result.addAll(second);
        return result;
    }

    /** 仍有成员持有时拒绝删除；与成员分配在同一把租户锁下判断，避免删除的同时被分配。 */
    public void delete(TenantActor actor, UUID id) {
        requireManager(actor);
        transactions.executeWithoutResult(status -> {
            roles.lockTenant(actor.tenantId());
            if (requireCustom(actor, id).memberCount() > 0) throw new ForgeException(RoleErrorCode.IN_USE);
            grants.deleteByRole(id);
            dataPolicies.deleteByRole(id);
            roles.delete(actor.tenantId(), id, actor.identityId());
        });
        audits.record(audit(actor, "role.delete", "role", id).build());
    }

    /** 一页成员及其角色。 */
    public List<MemberRoles> page(TenantActor actor, long offset, int limit) {
        requireManager(actor);
        List<Member> members = memberships.page(actor.tenantId(), offset, limit);
        if (members.isEmpty()) return List.of();
        Map<UUID, List<UUID>> rolesOf = new HashMap<>();
        for (MembershipRoleRow row : roles.rolesOf(
                actor.tenantId(), members.stream().map(Member::membershipId).toList())) {
            rolesOf.computeIfAbsent(row.membershipId(), key -> new ArrayList<>())
                    .add(row.roleId());
        }
        return members.stream()
                .map(member -> new MemberRoles(
                        member.membershipId(),
                        member.account(),
                        member.displayName(),
                        member.status(),
                        rolesOf.getOrDefault(member.membershipId(), List.of())))
                .toList();
    }

    public long count(TenantActor actor) {
        requireManager(actor);
        return memberships.count(actor.tenantId());
    }

    /**
     * 整体替换成员的角色，可多选，权限取并集。Owner 不在分配范围内：成员持有的 Owner 保持不变，只能经转让变更（工单 17）。
     */
    public void assign(TenantActor actor, UUID membershipId, Collection<UUID> roleIds) {
        requireManager(actor);
        Set<UUID> assigned = new LinkedHashSet<>(roleIds);
        boolean changed = Boolean.TRUE.equals(transactions.execute(status -> {
            roles.lockTenant(actor.tenantId());
            if (!memberships.exists(actor.tenantId(), membershipId)) {
                throw new ForgeException(RoleErrorCode.MEMBERSHIP_NOT_FOUND);
            }
            if (!assigned.isEmpty() && roles.countAssignable(actor.tenantId(), assigned) != assigned.size()) {
                throw new ForgeException(RoleErrorCode.INVALID_ASSIGNMENT);
            }
            int rows = roles.revokeExcept(membershipId, assigned);
            if (!assigned.isEmpty()) rows += roles.grant(actor.tenantId(), membershipId, assigned);
            return rows > 0;
        }));
        if (changed) publish(List.of(membershipId));
        audits.record(audit(actor, "membership.rolesAssign", "membership", membershipId)
                .detail("roleIds", List.copyOf(assigned))
                .build());
    }

    private void requireManager(TenantActor actor) {
        roleService.requirePermission(actor.membershipId(), IamPermissions.ROLE_MANAGE);
    }

    private static AuditEvent.Builder audit(TenantActor actor, String action, String resourceType, UUID resourceId) {
        return AuditEvent.builder(AuditRealm.TENANT, AuditCategory.PERMISSION, action)
                .tenant(actor.tenantId())
                .actor(actor.identityId())
                .resource(resourceType, resourceId);
    }

    private RoleRow requireCustom(TenantActor actor, UUID id) {
        RoleRow role = roles.find(actor.tenantId(), id);
        if (role == null) throw new ForgeException(RoleErrorCode.NOT_FOUND);
        if (role.builtin()) throw new ForgeException(RoleErrorCode.BUILTIN);
        return role;
    }

    // 只能授予当前有效且在 Plan 内的权限码：已下线或超出 Plan 的编码不在授权界面中，直接调用接口也被拒绝。
    private Set<String> requireGrantable(UUID tenantId, Collection<String> codes) {
        Set<String> requested = new LinkedHashSet<>(codes);
        Set<String> active = new HashSet<>(permissions.activeCodes());
        Set<String> grantable = grantableCodes(tenantId);
        for (String code : requested) {
            if (!active.contains(code)) throw new ForgeException(RoleErrorCode.INVALID_PERMISSION, code);
            if (!grantable.contains(code)) throw new ForgeException(RoleErrorCode.NOT_IN_PLAN, code);
        }
        return requested;
    }

    private Set<String> grantableCodes(UUID tenantId) {
        Set<String> codes = new HashSet<>();
        for (PermissionRow row : roleService.grantablePermissions(tenantId)) codes.add(row.code());
        return codes;
    }

    private Set<String> declaredResources() {
        Set<String> resources = new HashSet<>();
        for (ServiceDataResource current : permissions.currentDataResources())
            resources.add(current.resource().code());
        return resources;
    }

    // 已存储的条件经 jsonb 规范化，键序与空白可能与请求不同，按 JSON 树比较，避免未改动时也递增持有者的版本。
    private boolean same(List<DataPolicy> current, List<DataPolicy> requested) {
        if (current.size() != requested.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            DataPolicy a = current.get(i);
            DataPolicy b = requested.get(i);
            if (!a.resource().equals(b.resource()) || !a.scope().equals(b.scope())) return false;
            if (a.condition() == null || b.condition() == null) {
                if (!Objects.equals(a.condition(), b.condition())) return false;
            } else if (!json.readTree(a.condition()).equals(json.readTree(b.condition()))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> sorted(Set<String> codes) {
        return codes.stream().sorted().toList();
    }

    // 只能在事务提交之后调用，理由同组织的 SubjectChangePublisher：Redis 只允许版本前进，不能领先于数据库。
    private void publish(List<UUID> membershipIds) {
        for (UUID membershipId : membershipIds) {
            try {
                membershipStates.incrementVersion(membershipId);
            } catch (MembershipNotFoundException ignored) {
                // 成员在授权变更提交后被删除，不再需要发布。
            }
        }
    }
}
