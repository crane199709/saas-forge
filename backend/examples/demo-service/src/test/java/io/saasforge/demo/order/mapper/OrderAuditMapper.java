package io.saasforge.demo.order.mapper;

import io.saasforge.demo.order.entity.Order;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 测试专用：逻辑删除后的行对 MyBatis-Plus 内置方法不可见，标准列测试须经手写查询读出已删除行的标准列。 */
@Mapper
public interface OrderAuditMapper {

    /**
     * 按 id 查询当前租户的订单，包含已逻辑删除的行。租户条件由 SDK 注入。
     *
     * @param id 订单 id
     * @return 订单（含标准列），不存在时为 {@code null}
     */
    Order selectIncludingDeleted(@Param("id") UUID id);
}
