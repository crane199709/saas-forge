package io.saasforge.demo.order.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.model.OrderSort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 订单是 Data Resource「order」：BaseMapper 的查询、更新与删除都按当前成员的 Data Policy 过滤。 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 按排序条件分页查询当前租户的订单，最后按 id 排序保证翻页稳定。租户条件与当前成员的 Data Policy 条件由 SDK 注入。
     *
     * @param sorts 排序条件，列名须经 {@link OrderSort#from} 由白名单字段转换而来
     * @param limit 每页条数
     * @param offset 偏移量
     * @return 当前页订单
     */
    List<Order> selectPage(
            @Param("sorts") List<OrderSort> sorts, @Param("limit") int limit, @Param("offset") long offset);

    /** 条件关闭未支付订单，重复调用不刷新审计列；租户和 Data Policy 由 SDK 注入。 */
    int closeUnpaid(@Param("id") UUID id, @Param("actor") UUID actor);

    /**
     * 列出当前租户超时的未支付订单 ID；租户与 Data Policy 条件由 SDK 注入，系统任务无成员限制。
     * 随后条件更新每条订单，才能只为实际变化的订单发出同事务的待办关闭事件。
     */
    List<UUID> expiredUnpaid(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * 冷静期结束后把当前租户的订单全部标记删除，返回受影响行数。租户条件由 SDK 注入；清理针对整个租户，不受 Data Policy 过滤。
     */
    @InterceptorIgnore(dataPermission = "true")
    int markTenantDeleted();
}
