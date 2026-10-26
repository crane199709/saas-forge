package io.saasforge.demo.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.mapper.OrderMapper;
import io.saasforge.demo.order.model.OrderSort;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.web.PageQuery;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 演示业务代码不含任何租户条件，隔离完全由 SDK 完成。 */
@Service
public class OrderService {
    /** 可排序字段到列名的白名单；排序列名经此映射后才进入 Mapper XML 的 {@code ${}}。 */
    private static final Map<String, String> SORT_COLUMNS = Map.of("createdAt", "created_at", "amount", "amount");

    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public Order create(String title, BigDecimal amount) {
        var order = new Order();
        order.setTitle(title);
        order.setAmount(amount);
        orderMapper.insert(order);
        // 创建时间由数据库生成，重新读取才能返回与之后查询一致的值
        return get(order.getId());
    }

    public List<Order> list(PageQuery query) {
        List<OrderSort> sorts = query.sorts().stream()
                .map(sort -> new OrderSort(
                        SORT_COLUMNS.get(sort.field()), sort.direction().name()))
                .toList();
        return orderMapper.selectPage(sorts, query.size(), query.offset());
    }

    public long count() {
        return orderMapper.selectCount(new LambdaQueryWrapper<>());
    }

    public Order get(UUID id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw notFound(id);
        }
        return order;
    }

    public Order update(UUID id, String title, BigDecimal amount) {
        var order = new Order();
        order.setId(id);
        order.setTitle(title);
        order.setAmount(amount);
        if (orderMapper.updateById(order) == 0) {
            throw notFound(id);
        }
        return get(id);
    }

    public void delete(UUID id) {
        if (orderMapper.deleteById(id) == 0) {
            throw notFound(id);
        }
    }

    private static ForgeException notFound(UUID id) {
        return ForgeException.withErrorMessage(
                OrderErrorCode.NOT_FOUND, "Order " + id + " not found in current tenant");
    }
}
