package io.saasforge.platform.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.platform.plan.entity.Plan;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PlanMapper extends BaseMapper<Plan> {
    /**
     * 未删除的 Plan，并对该行加共享锁至事务结束：分配给租户与修改、删除该 Plan 串行，分配时读到的内容不会被并发修改覆盖。
     * 不存在时为 null。平台全局表，不受 Data Policy 过滤。
     */
    Plan lockShared(@Param("id") UUID id);

    /** 未删除的 Plan，并对该行加排他锁至事务结束，供修改与删除使用；不存在时为 null。平台全局表，不受 Data Policy 过滤。 */
    Plan lockExclusive(@Param("id") UUID id);
}
