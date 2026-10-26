package io.saasforge.demo.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.mapper.OrderMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 演示业务代码不含任何租户条件，隔离完全由 SDK 完成。 */
@Service
public class OrderService {
    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public Order create(String title, BigDecimal amount) {
        var order = new Order();
        order.setTitle(title);
        order.setAmount(amount);
        orderMapper.insert(order);
        return order;
    }

    public List<Order> list() {
        return orderMapper.selectList(new LambdaQueryWrapper<Order>().orderByAsc(Order::getId));
    }

    public Order get(UUID id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new OrderNotFoundException(id);
        }
        return order;
    }

    public Order update(UUID id, String title, BigDecimal amount) {
        var order = new Order();
        order.setId(id);
        order.setTitle(title);
        order.setAmount(amount);
        if (orderMapper.updateById(order) == 0) {
            throw new OrderNotFoundException(id);
        }
        return order;
    }

    public void delete(UUID id) {
        if (orderMapper.deleteById(id) == 0) {
            throw new OrderNotFoundException(id);
        }
    }
}
