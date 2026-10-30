#set( $dollar = '$' )
#set( $hash = '#' )
package ${package}.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ${package}.category.entity.OrderCategory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderCategoryMapper extends BaseMapper<OrderCategory> {
    /**
     * 冷静期结束后把当前租户的订单分类全部标记删除，返回受影响行数。租户条件由 SDK 注入，不受 Data Policy 过滤。
     */
    int markTenantDeleted();
}
