package io.saasforge.iam.plan.service;

import io.saasforge.iam.membership.service.MembershipStateService;
import io.saasforge.sdk.mq.EventHandler;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.provisioning.TenantPlanChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 订阅 platform 广播的 Plan 变更，更新租户 Plan 的副本，并递增该租户全部正常成员的 version：降级后超出 Plan 的授权
 * 在成员下一次请求即失效（记录保留），升级后刷新即恢复。
 *
 * <p>版本在消费事务内递增并发布到 Redis，理由同 {@code TenantStateService#apply}：Redis 写入失败则事务回滚、消息重投；
 * 提交失败时 Redis 已领先，重投会在数据库中再递增一次追上同一版本。
 */
@Service
class TenantPlanChangedHandler implements EventHandler<TenantPlanChanged> {
    private static final Logger log = LoggerFactory.getLogger(TenantPlanChangedHandler.class);

    private final PlanService plans;
    private final MembershipStateService membershipStates;

    TenantPlanChangedHandler(PlanService plans, MembershipStateService membershipStates) {
        this.plans = plans;
        this.membershipStates = membershipStates;
    }

    @Override
    public String topic() {
        return TenantCreated.TOPIC;
    }

    @Override
    public Class<TenantPlanChanged> eventType() {
        return TenantPlanChanged.class;
    }

    @Override
    public void handle(TenantPlanChanged event) {
        if (!plans.apply(event)) {
            log.info(
                    "Skipped stale plan change of tenant {} to {} (version {})",
                    event.tenantId(),
                    event.planCode(),
                    event.version());
            return;
        }
        membershipStates.incrementActiveVersions(event.tenantId());
    }
}
