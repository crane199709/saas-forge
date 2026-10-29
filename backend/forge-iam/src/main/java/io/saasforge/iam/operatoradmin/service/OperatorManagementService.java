package io.saasforge.iam.operatoradmin.service;

import io.saasforge.iam.operator.model.OperatorRow;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.operator.service.OperatorStateService;
import io.saasforge.iam.operatoradmin.model.ManagedOperator;
import io.saasforge.iam.permission.service.PlatformPermissions;
import io.saasforge.iam.platformrole.service.PlatformRoleService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 持有 {@value PlatformPermissions#OPERATOR_MANAGE} 的 Operator 管理 Operator 账号：创建、编辑、停用与恢复、重置密码、分配平台角色。
 * 停用、角色或负责区域变化在事务提交后递增该 Operator 的 version，旧 Token 立即失效（停用后也不能再刷新）。
 *
 * <p>始终保留至少一位状态正常的超级管理员：停用或撤销最后一位时拒绝，否则无人能再分配角色。分配超级管理员、操作持有超级管理员的
 * Operator 只有超级管理员可以做。
 */
@Service
public class OperatorManagementService {
    private final OperatorService operators;
    private final OperatorStateService operatorStates;
    private final PlatformRoleService roles;
    private final TransactionTemplate transactions;

    OperatorManagementService(
            OperatorService operators,
            OperatorStateService operatorStates,
            PlatformRoleService roles,
            TransactionTemplate transactions) {
        this.operators = operators;
        this.operatorStates = operatorStates;
        this.roles = roles;
        this.transactions = transactions;
    }

    /** 全部未删除的 Operator 及其平台角色，按账号排序。 */
    public List<ManagedOperator> list(UUID actor) {
        requireManager(actor);
        Map<UUID, List<UUID>> rolesOf = roles.assignments();
        return operators.list().stream()
                .map(operator -> new ManagedOperator(operator, rolesOf.getOrDefault(operator.id(), List.of())))
                .toList();
    }

    /** 新建 Operator 并分配平台角色，同一事务写入；新 Operator 还没有 Token，不需要递增版本。 */
    public ManagedOperator create(
            UUID actor, String account, String displayName, String password, String region, Collection<UUID> roleIds) {
        requireManager(actor);
        roles.requireSuperAdminFor(actor, null, roleIds);
        UUID id = transactions.execute(status -> {
            roles.lock();
            UUID created = operators.create(account, displayName, password, region, actor);
            roles.assign(created, roleIds);
            return created;
        });
        return new ManagedOperator(operators.get(id), List.copyOf(roleIds));
    }

    /** 修改显示名与负责区域；负责区域是 Data Policy 的主体属性，变化后该 Operator 随即按新范围处理。 */
    public void update(UUID actor, UUID id, String displayName, String region) {
        requireManager(actor);
        roles.requireSuperAdminFor(actor, id, List.of());
        boolean regionChanged = Boolean.TRUE.equals(transactions.execute(status -> {
            OperatorRow current = operators.get(id);
            operators.update(id, displayName, region, actor);
            return !Objects.equals(current.region(), region);
        }));
        if (regionChanged) operatorStates.incrementVersion(id);
    }

    /** 停用后该 Operator 的 Token 立即失效，且不能再登录或刷新；已停用时为无操作。 */
    public void disable(UUID actor, UUID id) {
        changeStatus(actor, id, OperatorService.DISABLED);
    }

    /** 恢复为可登录；已签发的 Token 已在停用时失效，需重新登录。 */
    public void enable(UUID actor, UUID id) {
        changeStatus(actor, id, OperatorService.ACTIVE);
    }

    /**
     * 由管理员设置新密码；已签发的 Access Token 随即失效。已有的登录会话（Refresh Token）不会终止，需要立即踢出时应停用。
     */
    public void resetPassword(UUID actor, UUID id, String password) {
        requireManager(actor);
        roles.requireSuperAdminFor(actor, id, List.of());
        operators.changePassword(id, password, actor);
        operatorStates.incrementVersion(id);
    }

    /** 整体替换 Operator 的平台角色，可多选，权限取并集；该 Operator 下一次请求起按新权限处理。 */
    public void assign(UUID actor, UUID id, Collection<UUID> roleIds) {
        requireManager(actor);
        roles.requireSuperAdminFor(actor, id, roleIds);
        boolean changed = Boolean.TRUE.equals(transactions.execute(status -> {
            roles.lock();
            operators.get(id);
            return roles.assign(id, roleIds);
        }));
        if (changed) operatorStates.incrementVersion(id);
    }

    private void changeStatus(UUID actor, UUID id, String status) {
        requireManager(actor);
        roles.requireSuperAdminFor(actor, id, List.of());
        boolean changed = Boolean.TRUE.equals(transactions.execute(tx -> {
            roles.lock();
            operators.get(id);
            if (!operators.changeStatus(id, status, actor)) return false;
            if (OperatorService.DISABLED.equals(status)) roles.requireSuperAdminRemains();
            return true;
        }));
        if (changed) operatorStates.incrementVersion(id);
    }

    private void requireManager(UUID actor) {
        roles.requirePermission(actor, PlatformPermissions.OPERATOR_MANAGE);
    }
}
