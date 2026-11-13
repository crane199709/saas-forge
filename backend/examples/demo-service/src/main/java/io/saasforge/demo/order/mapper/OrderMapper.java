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

    /** 锁定将被覆盖的可见订单，锁一直保留到导入确认事务结束；租户与 Data Policy 由 SDK 注入。 */
    Order selectForImport(@Param("id") UUID id);

    /** 只覆盖导入模型声明的三个字段，空客户来源同样覆盖；标准列由 SQL 维护。 */
    int overwriteImported(
            @Param("id") UUID id,
            @Param("title") String title,
            @Param("amount") java.math.BigDecimal amount,
            @Param("customerSource") String customerSource);

    /** 分页查询已删除订单；排序白名单与普通列表一致，租户与 Data Policy 条件由 SDK 注入。 */
    List<Order> selectDeletedPage(
            @Param("sorts") List<OrderSort> sorts, @Param("limit") int limit, @Param("offset") long offset);

    /** 统计当前租户中当前成员数据范围内的已删除订单，租户与 Data Policy 条件由 SDK 注入。 */
    long countDeleted();

    /** 锁住当前租户中当前成员可恢复的已删除订单，与删除 UPDATE 共用行锁；租户与 Data Policy 由 SDK 注入。 */
    Order selectDeletedForUpdate(@Param("id") UUID id);

    /** 恢复已删除订单并刷新审计列；租户与 Data Policy 由 SDK 注入，条件更新防止重复恢复。 */
    int restoreDeleted(@Param("id") UUID id, @Param("actorId") UUID actorId);

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
