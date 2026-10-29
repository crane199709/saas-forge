package io.saasforge.platform.plan.service;

import io.saasforge.platform.plan.entity.Plan;
import java.util.UUID;

/**
 * Plan 被租户使用的情况。由租户功能包实现：租户功能包分配 Plan 时要经本包读取与加锁，
 * 本包只依赖这个接口，两个功能包之间不形成循环。
 */
public interface PlanUsage {
    /** 是否仍有尚未清理（冷静期未结束）的租户使用该 Plan。 */
    boolean inUse(UUID planId);

    /** Plan 的内容已修改：向使用它的每个租户广播变更后的 Plan。在修改 Plan 的同一事务中调用。 */
    void planChanged(Plan plan);
}
