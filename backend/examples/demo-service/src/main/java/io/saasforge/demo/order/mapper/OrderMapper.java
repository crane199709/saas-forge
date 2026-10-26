package io.saasforge.demo.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.model.OrderSort;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 按排序条件分页查询当前租户的订单，最后按 id 排序保证翻页稳定。租户条件由 SDK 注入，不受 Data Policy 过滤。
     *
     * @param sorts 排序条件，列名须来自服务端白名单
     * @param limit 每页条数
     * @param offset 偏移量
     * @return 当前页订单
     */
    List<Order> selectPage(
            @Param("sorts") List<OrderSort> sorts, @Param("limit") int limit, @Param("offset") long offset);
}
