package io.saasforge.iam.platformrole.service;

import io.saasforge.iam.operator.service.OperatorStateService;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.permission.model.ServiceDataResource;
import io.saasforge.iam.permission.service.PermissionService;
import io.saasforge.iam.permission.service.PlatformPermissions;
import io.saasforge.iam.platformrole.mapper.PlatformRoleMapper;
import io.saasforge.iam.platformrole.model.PlatformRoleDetail;
import io.saasforge.iam.platformrole.model.PlatformRoleRow;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.DataResourceOption;
import io.saasforge.iam.role.model.RoleDataPolicyRow;
import io.saasforge.iam.role.model.RolePermissionRow;
import io.saasforge.iam.role.service.DataConditionService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持有 {@value PlatformPermissions#ROLE_MANAGE} 的 Operator 维护平台角色、角色授权与 Data Policy。规则同租户侧的角色管理：
 * 授权与策略整体替换，已下线的平台权限码与数据资源上的记录保留；变更影响到的 Operator 在事务提交后递增 version，旧 Token 立即失效。
 *
 * <p>与 {@link PlatformRoleService} 分开，理由同 {@code RoleManagementService}：发布明细的 {@link OperatorStateService} 经
 * {@link PlatformRoleService} 读取授权，本服务又要经它递增版本。
 */
@Service
public class PlatformRoleManagementService {
    private final PlatformRoleMapper roles;
    private final PlatformRoleService roleService;
    private final PermissionService permissions;
    private final OperatorStateService operatorStates;
    private final TransactionTemplate transactions;
    private final AuditRecorder audits;

    PlatformRoleManagementService(
            PlatformRoleMapper roles,
            PlatformRoleService roleService,
            PermissionService permissions,
            OperatorStateService operatorStates,
            TransactionTemplate transactions,
            AuditRecorder audits) {
        this.roles = roles;
        this.roleService = roleService;
        this.permissions = permissions;
        this.operatorStates = operatorStates;
        this.transactions = transactions;
        this.audits = audits;
    }

    /** 全部平台角色及其有效授权与 Data Policy；管理 Operator 账号时也要据此选择要分配的角色。 */
    public List<PlatformRoleDetail> list(UUID actor) {
        roleService.requirePermission(actor, PlatformPermissions.ROLE_MANAGE, PlatformPermissions.OPERATOR_MANAGE);
        Map<UUID, List<String>> codesOf = new HashMap<>();
        for (RolePermissionRow row : roles.permissionsByRole()) {
            codesOf.computeIfAbsent(row.roleId(), key -> new ArrayList<>()).add(row.code());
        }
        Map<UUID, List<DataPolicy>> policiesOf = new HashMap<>();
        for (RoleDataPolicyRow row : roles.policiesByRole()) {
            policiesOf
                    .computeIfAbsent(row.roleId(), key -> new ArrayList<>())
                    .add(new DataPolicy(row.resource(), row.scope(), row.condition()));
        }
        return roles.list().stream()
                .map(row -> PlatformRoleDetail.of(
                        row, codesOf.getOrDefault(row.id(), List.of()), policiesOf.getOrDefault(row.id(), List.of())))
                .toList();
    }

    /** 可授予的平台 Permission Code：只含 Platform Realm 服务申报且有效的，按服务、分组、编码排序。 */
    public List<PermissionRow> grantable(UUID actor) {
        requireManager(actor);
        return permissions.activePlatformPermissions();
    }

    /** Platform Realm 服务当前声明的 Data Resource，带可用的预设与资源属性。 */
    public List<DataResourceOption> dataResources(UUID actor) {
        requireManager(actor);
        return DataConditionService.optionsOf(permissions.currentPlatformDataResources());
    }

    /** 新建平台角色，授权与 Data Policy 同一事务写入；新角色还没有持有者，不需要递增版本。 */
    public PlatformRoleDetail create(
            UUID actor, String code, String name, Collection<String> codes, Collection<DataPolicy> policies) {
        requireManager(actor);
        Set<String> granted = requireGrantable(codes);
        List<DataPolicy> scoped = requireValid(policies);
        // 保留编码由数据库 CHECK 兜底，这里先拒绝，避免以 500 暴露约束错误。
        if (PlatformRoleService.SUPER_ADMIN.equals(code))
            throw new ForgeException(PlatformRoleErrorCode.CODE_TAKEN, code);
        UUID id = UuidV7.generate();
        transactions.executeWithoutResult(status -> {
            if (roles.insert(id, code, name, actor) == 0)
                throw new ForgeException(PlatformRoleErrorCode.CODE_TAKEN, code);
            if (!granted.isEmpty()) roles.grant(id, granted);
            if (!scoped.isEmpty()) roles.insertPolicies(id, scoped);
        });
        audits.record(audit(actor, "platformRole.create", "platformRole", id)
                .detail("code", code)
                .detail("name", name)
                .detail("permissions", granted.stream().sorted().toList())
                .detail("dataPolicies", scoped)
                .build());
        return PlatformRoleDetail.of(
                new PlatformRoleRow(id, code, name, false, 0),
                granted.stream().sorted().toList(),
                scoped);
    }

    /**
     * 改名并整体替换授权与当前声明的 Data Resource 上的 Data Policy；已下线的权限码与资源上的记录保留。
     * 持有该角色的 Operator 随即按新权限处理。
     */
    public void update(UUID actor, UUID id, String name, Collection<String> codes, Collection<DataPolicy> policies) {
        requireManager(actor);
        Set<String> granted = requireGrantable(codes);
        List<DataPolicy> scoped = requireValid(policies);
        Set<String> revoked = activeCodes();
        revoked.removeAll(granted);
        Set<String> declared = new HashSet<>();
        for (ServiceDataResource current : permissions.currentPlatformDataResources()) {
            declared.add(current.resource().code());
        }
        List<UUID> affected = transactions.execute(status -> {
            roleService.lock();
            requireCustom(id);
            roles.rename(id, name, actor);
            int changed = revoked.isEmpty() ? 0 : roles.revoke(id, revoked);
            if (!granted.isEmpty()) changed += roles.grant(id, granted);
            List<DataPolicy> current = roles.policiesOfRole(id).stream()
                    .filter(policy -> declared.contains(policy.resource()))
                    .toList();
            if (!current.equals(scoped)) {
                if (!declared.isEmpty()) roles.deletePolicies(id, declared);
                if (!scoped.isEmpty()) roles.insertPolicies(id, scoped);
                changed++;
            }
            return changed == 0 ? List.<UUID>of() : roles.holders(id);
        });
        affected.forEach(operatorStates::incrementVersion);
        audits.record(audit(actor, "platformRole.update", "platformRole", id)
                .detail("name", name)
                .detail("permissions", granted.stream().sorted().toList())
                .detail("dataPolicies", scoped)
                .build());
    }

    /** 仍有 Operator 持有时拒绝删除；与角色分配在同一把锁下判断，避免删除的同时被分配。 */
    public void delete(UUID actor, UUID id) {
        requireManager(actor);
        transactions.executeWithoutResult(status -> {
            roleService.lock();
            if (requireCustom(id).operatorCount() > 0) throw new ForgeException(PlatformRoleErrorCode.IN_USE);
            roles.deletePermissions(id);
            roles.deleteAllPolicies(id);
            roles.delete(id, actor);
        });
        audits.record(audit(actor, "platformRole.delete", "platformRole", id).build());
    }

    private void requireManager(UUID actor) {
        roleService.requirePermission(actor, PlatformPermissions.ROLE_MANAGE);
    }

    private static AuditEvent.Builder audit(UUID actor, String action, String resourceType, UUID resourceId) {
        return AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.PERMISSION, action)
                .actor(actor)
                .resource(resourceType, resourceId);
    }

    private PlatformRoleRow requireCustom(UUID id) {
        PlatformRoleRow role = roles.find(id);
        if (role == null) throw new ForgeException(PlatformRoleErrorCode.NOT_FOUND);
        if (role.builtin()) throw new ForgeException(PlatformRoleErrorCode.BUILTIN);
        return role;
    }

    // 租户权限码不在平台的可授予范围内：即使直接调用接口也被拒绝，平台与租户的授权互不渗透。
    private Set<String> requireGrantable(Collection<String> codes) {
        Set<String> requested = new LinkedHashSet<>(codes);
        Set<String> active = activeCodes();
        for (String code : requested) {
            if (!active.contains(code)) throw new ForgeException(PlatformRoleErrorCode.INVALID_PERMISSION, code);
        }
        return requested;
    }

    private Set<String> activeCodes() {
        Set<String> codes = new HashSet<>();
        for (PermissionRow row : permissions.activePlatformPermissions()) codes.add(row.code());
        return codes;
    }

    // 平台数据资源与 Operator 的属性（负责区域）组成属性目录，校验规则与租户角色相同。
    private List<DataPolicy> requireValid(Collection<DataPolicy> policies) {
        return DataConditionService.requireValid(
                permissions.currentPlatformDataResources(), OperatorStateService.ATTRIBUTE_TYPES, policies);
    }
}
