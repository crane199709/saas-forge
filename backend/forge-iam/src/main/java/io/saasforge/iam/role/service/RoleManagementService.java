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
import io.saasforge.iam.role.model.ConditionOptions;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.DataResourceOption;
import io.saasforge.iam.role.model.MemberRoles;
import io.saasforge.iam.role.model.MembershipRoleRow;
import io.saasforge.iam.role.model.PolicyPreview;
import io.saasforge.iam.role.model.RoleDataPolicyRow;
import io.saasforge.iam.role.model.RoleDetail;
import io.saasforge.iam.role.model.RolePermissionRow;
import io.saasforge.iam.role.model.RoleRow;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tenant Admin 维护自定义角色、角色授权、角色上的 Data Policy 与成员的角色分配。变更影响到的成员在事务提交后递增 version，
 * 旧 Token 立即失效，刷新后按新权限签发。
 *
 * <p>与 {@link RoleService} 分开：发布明细的 {@link MembershipStateService} 经 {@link RoleService} 读取授权，
 * 本服务又要经它递增版本，合在一起会形成 Bean 循环依赖。
 */
@Service
public class RoleManagementService {
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
            JsonMapper json) {
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
    }

    /** 租户的全部角色及其有效授权与 Data Policy，含开通时按业务服务模板预置的角色。 */
    public List<RoleDetail> list(TenantActor actor) {
        requireManager(actor);
        Map<UUID, List<String>> codesOf = new HashMap<>();
        for (RolePermissionRow row : grants.listByTenant(actor.tenantId())) {
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
    }

    /** 可授予的 Permission Code：只含有效的，已下线的不再出现。 */
    public List<PermissionRow> grantable(TenantActor actor) {
        requireManager(actor);
        return permissions.activePermissions();
    }

    /** 新建自定义角色，权限码授权与 Data Policy 同一事务写入；新角色还没有持有者，不需要递增版本。 */
    public RoleDetail create(
            TenantActor actor, String code, String name, Collection<String> codes, Collection<DataPolicy> policies) {
        requireManager(actor);
        Set<String> granted = requireActive(codes);
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
        return RoleDetail.of(new RoleRow(id, code, name, false, false, 0), sorted(granted), scoped);
    }

    public void rename(TenantActor actor, UUID id, String name) {
        requireManager(actor);
        requireCustom(actor, id);
        if (roles.rename(actor.tenantId(), id, name, actor.identityId()) == 0) {
            throw new ForgeException(RoleErrorCode.NOT_FOUND);
        }
    }

    /** 整体替换角色的有效授权；已下线权限码上的授权保留，权限码恢复后随之恢复。持有该角色的成员随即失去或获得权限。 */
    public void replacePermissions(TenantActor actor, UUID id, Collection<String> codes) {
        requireManager(actor);
        Set<String> granted = requireActive(codes);
        List<UUID> affected = transactions.execute(status -> {
            roles.lockTenant(actor.tenantId());
            requireCustom(actor, id);
            int changed = grants.revokeActiveExcept(id, granted);
            if (!granted.isEmpty()) changed += grants.grant(id, granted);
            return changed == 0 ? List.<UUID>of() : roles.holders(id);
        });
        publish(affected);
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
    }

    private void requireManager(TenantActor actor) {
        roleService.requirePermission(actor.membershipId(), IamPermissions.ROLE_MANAGE);
    }

    private RoleRow requireCustom(TenantActor actor, UUID id) {
        RoleRow role = roles.find(actor.tenantId(), id);
        if (role == null) throw new ForgeException(RoleErrorCode.NOT_FOUND);
        if (role.builtin()) throw new ForgeException(RoleErrorCode.BUILTIN);
        return role;
    }

    // 只能授予当前有效的权限码：已下线的编码不在授权界面中，直接调用接口也被拒绝。
    private Set<String> requireActive(Collection<String> codes) {
        Set<String> requested = new LinkedHashSet<>(codes);
        Set<String> active = new HashSet<>(permissions.activeCodes());
        for (String code : requested) {
            if (!active.contains(code)) throw new ForgeException(RoleErrorCode.INVALID_PERMISSION, code);
        }
        return requested;
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
