package io.saasforge.platform.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.platform.plan.entity.Plan;
import io.saasforge.platform.plan.mapper.PlanMapper;
import io.saasforge.platform.plan.model.CatalogPermission;
import io.saasforge.platform.plan.model.CatalogPermissionTree;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plan（规格 5.4）的维护：Feature Pack（可用的 Permission Code 与 Sub-App）与 Quota（成员数、存储容量、每月 Token 总量）。
 *
 * <p>修改 Plan 的内容即修改所有使用它的租户的上限，在同一事务中经 {@link PlanUsage} 向这些租户广播。
 */
@Service
public class PlanService {
    private final PlanMapper plans;
    private final PermissionCatalog catalog;
    private final PlanUsage usage;

    PlanService(PlanMapper plans, PermissionCatalog catalog, PlanUsage usage) {
        this.plans = plans;
        this.catalog = catalog;
        this.usage = usage;
    }

    /** 全部未删除的 Plan，按编码排序。 */
    public List<Plan> list() {
        return plans.selectList(new LambdaQueryWrapper<Plan>().orderByAsc(Plan::getCode));
    }

    public Plan get(UUID id) {
        Plan plan = plans.selectById(id);
        if (plan == null) {
            throw notFound(id);
        }
        return plan;
    }

    /**
     * 读取 Plan 并加共享锁至事务结束，供分配给租户时使用：与修改、删除该 Plan 串行，
     * 广播给租户的内容不会被并发的修改覆盖，也不会分配到正在删除的 Plan。
     */
    public Plan lockForAssignment(UUID id) {
        Plan plan = plans.lockShared(id);
        if (plan == null) {
            throw notFound(id);
        }
        return plan;
    }

    /** Feature Pack 可选的 Permission Code。 */
    public List<CatalogPermission> catalog() {
        return catalog.permissions();
    }

    /** Feature Pack 的菜单授权树，由 Operator 选择 Sub-App 后按页面勾选权限码。 */
    public CatalogPermissionTree catalogTree() {
        return catalog.tree();
    }

    /**
     * 新建 Plan。
     *
     * @param memberQuota 成员数上限，null 表示不限
     * @param storageQuotaMb 存储容量上限（MiB），null 表示不限
     * @param tokenQuota 每租户每月 Token 总量上限，null 表示不限
     * @throws ForgeException 409 {@code plan.codeTaken}；400 {@code plan.unknownPermission}
     */
    @Transactional
    public Plan create(
            String code,
            String name,
            Collection<String> permissions,
            Collection<String> subApps,
            Integer memberQuota,
            Long storageQuotaMb,
            Long tokenQuota) {
        Set<String> granted = sorted(permissions);
        requireKnown(granted, Set.of());
        var plan = new Plan();
        plan.setId(UuidV7.generate());
        plan.setCode(code);
        fill(plan, name, granted, subApps, memberQuota, storageQuotaMb, tokenQuota);
        try {
            plans.insert(plan);
        } catch (DuplicateKeyException e) {
            throw new ForgeException(PlanErrorCode.CODE_TAKEN, code);
        }
        return get(plan.getId());
    }

    /**
     * 整体替换 Plan 的名称、Feature Pack 与 Quota（编码不可修改），并向使用它的租户广播。只有新加入的权限码须在当前目录中：
     * 已从清单下线的编码仍可保留在 Feature Pack 里，业务服务重新上报后随之恢复。
     */
    @Transactional
    public Plan update(
            UUID id,
            String name,
            Collection<String> permissions,
            Collection<String> subApps,
            Integer memberQuota,
            Long storageQuotaMb,
            Long tokenQuota) {
        Plan plan = plans.lockExclusive(id);
        if (plan == null) {
            throw notFound(id);
        }
        Set<String> granted = sorted(permissions);
        requireKnown(granted, Set.copyOf(Arrays.asList(plan.getPermissions())));
        fill(plan, name, granted, subApps, memberQuota, storageQuotaMb, tokenQuota);
        plans.updateById(plan);
        Plan updated = get(id);
        usage.planChanged(updated);
        return updated;
    }

    /**
     * 删除 Plan（逻辑删除）。仍有尚未清理的租户使用时拒绝。
     *
     * @throws ForgeException 409 {@code plan.inUse}
     */
    @Transactional
    public void delete(UUID id) {
        if (plans.lockExclusive(id) == null) {
            throw notFound(id);
        }
        if (usage.inUse(id)) {
            throw new ForgeException(PlanErrorCode.IN_USE);
        }
        plans.deleteById(id);
    }

    // 只校验新加入的编码：读取目录要访问 iam，Plan 里已有的编码不因 iam 暂时下线了它而无法保存。
    private void requireKnown(Set<String> requested, Set<String> existing) {
        Set<String> added =
                requested.stream().filter(code -> !existing.contains(code)).collect(Collectors.toSet());
        if (added.isEmpty()) {
            return;
        }
        Set<String> known =
                catalog.permissions().stream().map(CatalogPermission::code).collect(Collectors.toSet());
        for (String code : new TreeSet<>(added)) {
            if (!known.contains(code)) {
                throw new ForgeException(PlanErrorCode.UNKNOWN_PERMISSION, code);
            }
        }
    }

    private static void fill(
            Plan plan,
            String name,
            Set<String> permissions,
            Collection<String> subApps,
            Integer memberQuota,
            Long storageQuotaMb,
            Long tokenQuota) {
        plan.setName(name);
        plan.setPermissions(permissions.toArray(String[]::new));
        plan.setSubApps(sorted(subApps).toArray(String[]::new));
        plan.setMemberQuota(memberQuota);
        plan.setStorageQuotaMb(storageQuotaMb);
        plan.setTokenQuota(tokenQuota);
    }

    private static Set<String> sorted(Collection<String> values) {
        return new TreeSet<>(values);
    }

    private static ForgeException notFound(UUID id) {
        return ForgeException.withErrorMessage(PlanErrorCode.NOT_FOUND, "Plan " + id + " not found");
    }
}
