package io.saasforge.platform.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.platform.plan.entity.Plan;
import io.saasforge.platform.plan.service.PlanService;
import io.saasforge.platform.tenant.config.ProvisioningProperties;
import io.saasforge.platform.tenant.entity.Tenant;
import io.saasforge.platform.tenant.entity.TenantInitialization;
import io.saasforge.platform.tenant.entity.TenantStatusChange;
import io.saasforge.platform.tenant.mapper.TenantInitializationMapper;
import io.saasforge.platform.tenant.mapper.TenantMapper;
import io.saasforge.platform.tenant.mapper.TenantStatusChangeMapper;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.core.CurrentActorProvider;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantInitializationRequested;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant 主数据与 Provisioning 编排（规格第 9 节）。 */
@Service
public class TenantService {
    // 初始化状态，与 Tenant Lifecycle 的 pending 无关
    static final String PENDING = "pending";
    static final String SUCCEEDED = "succeeded";
    static final String FAILED = "failed";
    // 清单中声明的 Data Resource 编码，见 META-INF/forge/manifest.json
    static final String DATA_RESOURCE = "tenant";

    private final TenantMapper tenants;
    private final TenantInitializationMapper initializations;
    private final EventPublisher events;
    private final ProvisioningProperties provisioning;
    private final TenantStatusChangeMapper statusChanges;
    private final CurrentActorProvider actors;
    private final PlanService plans;
    private final TenantPlanService tenantPlans;
    private final DataPermission dataPermission;

    TenantService(
            TenantMapper tenants,
            TenantInitializationMapper initializations,
            EventPublisher events,
            ProvisioningProperties provisioning,
            TenantStatusChangeMapper statusChanges,
            CurrentActorProvider actors,
            PlanService plans,
            TenantPlanService tenantPlans,
            DataPermission dataPermission) {
        this.tenants = tenants;
        this.initializations = initializations;
        this.events = events;
        this.provisioning = provisioning;
        this.statusChanges = statusChanges;
        this.actors = actors;
        this.plans = plans;
        this.tenantPlans = tenantPlans;
        this.dataPermission = dataPermission;
    }

    /**
     * 创建租户并发布 {@link TenantCreated} 与初始的 {@link TenantPlanChanged}，三者同一事务提交。租户立即可用，不等待各服务初始化完成。
     *
     * <p>初始状态与到期时间由 Operator 创建时选择：不做计费，Plan 只规定能力上限，不规定试用期。
     *
     * <p>新租户须在创建者自己的数据范围内（创建人即当前 Operator），否则创建出自己看不到的租户，以 403 拒绝。
     *
     * @param adminContact 初始管理员的邮箱或手机号，由 iam 据此邀请
     * @param region 租户所在区域，可为 null
     * @param status 初始状态，{@code trial} 或 {@code active}
     * @param expiresAt 试用或订购的到期时间，可为 null
     * @param planId 租户适用的 Plan
     * @throws ForgeException 404 {@code plan.notFound}；403 {@code auth.forbidden}
     */
    @Transactional
    public Tenant create(
            String name, String adminContact, String region, String status, OffsetDateTime expiresAt, UUID planId) {
        var attributes = new HashMap<String, Object>();
        attributes.put("ownerId", actors.currentActor().orElse(null));
        attributes.put("region", region);
        attributes.put("status", status);
        dataPermission.check(DATA_RESOURCE, attributes);
        Plan plan = plans.lockForAssignment(planId);
        var tenant = new Tenant();
        tenant.setId(UuidV7.generate());
        tenant.setName(name);
        tenant.setStatus(status);
        tenant.setStatusVersion(0L);
        tenant.setExpiresAt(expiresAt);
        tenant.setAdminContact(adminContact);
        tenant.setRegion(region);
        tenant.setPlanId(plan.getId());
        tenant.setPlanVersion(1L);
        tenants.insert(tenant);
        recordStatusChange(tenant.getId(), null, status, null);
        for (String service : provisioning.services()) {
            var initialization = new TenantInitialization();
            initialization.setTenantId(tenant.getId());
            initialization.setService(service);
            initialization.setStatus(PENDING);
            initializations.insert(initialization);
        }
        publish(tenant.getId(), createdEvent(tenant));
        tenantPlans.publish(tenant.getId(), plan, tenant.getPlanVersion());
        return get(tenant.getId());
    }

    /**
     * 为租户更换 Plan 并广播 {@link TenantPlanChanged}，二者同一事务提交。降级后超出新 Plan 的授权在各服务中随即失效（记录保留），
     * 升级后恢复；Plan 不变时不做任何事。
     *
     * @throws ForgeException 404 {@code tenant.notFound} 或 {@code plan.notFound}
     */
    @Transactional
    public Tenant changePlan(UUID tenantId, UUID planId) {
        Tenant tenant = get(tenantId);
        if (planId.equals(tenant.getPlanId())) return tenant;
        tenantPlans.assign(tenantId, plans.lockForAssignment(planId));
        return get(tenantId);
    }

    public List<Tenant> page(long offset, int limit) {
        return tenants.page(offset, limit);
    }

    public long count() {
        return tenants.selectCount(new LambdaQueryWrapper<>());
    }

    public Tenant get(UUID tenantId) {
        Tenant tenant = tenants.selectById(tenantId);
        if (tenant == null) {
            throw ForgeException.withErrorMessage(TenantErrorCode.NOT_FOUND, "Tenant " + tenantId + " not found");
        }
        return tenant;
    }

    /** 各服务的初始化状态，按服务名排序。 */
    public List<TenantInitialization> initializations(UUID tenantId) {
        return initializations.selectList(new LambdaQueryWrapper<TenantInitialization>()
                .eq(TenantInitialization::getTenantId, tenantId)
                .orderByAsc(TenantInitialization::getService));
    }

    /**
     * 让一个初始化失败的服务重新初始化：状态回到 pending，并向该服务发布 {@link TenantInitializationRequested}，二者同一事务提交。
     */
    @Transactional
    public TenantInitialization retry(UUID tenantId, String service) {
        Tenant tenant = get(tenantId);
        TenantInitialization initialization = initializations.selectOne(new LambdaQueryWrapper<TenantInitialization>()
                .eq(TenantInitialization::getTenantId, tenantId)
                .eq(TenantInitialization::getService, service));
        if (initialization == null) {
            throw ForgeException.withErrorMessage(
                    TenantErrorCode.INITIALIZATION_NOT_FOUND,
                    "Tenant " + tenantId + " has no initialization of service " + service);
        }
        if (!FAILED.equals(initialization.getStatus())) {
            throw ForgeException.withErrorMessage(
                    TenantErrorCode.INITIALIZATION_NOT_FAILED,
                    "Initialization of service " + service + " is " + initialization.getStatus());
        }
        initialization.setStatus(PENDING);
        initialization.setError(null);
        initializations.updateById(initialization);
        publish(tenantId, new TenantInitializationRequested(service, createdEvent(tenant)));
        return initializations.selectById(initialization.getId());
    }

    /** 记录一次状态变更，{@code from} 为 null 表示创建。操作人取自当前请求，系统发起时为 NULL。 */
    void recordStatusChange(UUID tenantId, String from, String to, String reason) {
        var change = new TenantStatusChange();
        change.setTenantId(tenantId);
        change.setFromStatus(from);
        change.setToStatus(to);
        change.setReason(reason);
        change.setChangedBy(actors.currentActor().orElse(null));
        statusChanges.insert(change);
    }

    /** 记录服务回报的初始化结果，由回报消费者调用。 */
    public void report(UUID tenantId, String service, boolean succeeded, String error) {
        initializations.report(UuidV7.generate(), tenantId, service, succeeded ? SUCCEEDED : FAILED, error);
    }

    // 事件携带新租户的上下文，订阅方恢复后其初始化数据照常按租户隔离；平台自身的表不按租户隔离，不受影响。
    private void publish(UUID tenantId, Object event) {
        CurrentTenant.run(tenantId, () -> events.publish(TenantCreated.TOPIC, event));
    }

    private static TenantCreated createdEvent(Tenant tenant) {
        return new TenantCreated(tenant.getId(), tenant.getName(), tenant.getAdminContact(), tenant.getStatus());
    }
}
