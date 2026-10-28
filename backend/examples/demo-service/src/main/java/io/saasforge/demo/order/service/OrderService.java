package io.saasforge.demo.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.mapper.OrderMapper;
import io.saasforge.demo.order.model.OrderCreated;
import io.saasforge.demo.order.model.OrderSort;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.web.PageQuery;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演示业务代码不含任何租户与数据权限条件：隔离与按 Data Scope 过滤都由 SDK 注入 SQL。
 *
 * <p>超出数据范围的订单与不存在的订单一样返回 {@code order.notFound}，不泄露它是否存在。
 */
@Service
public class OrderService implements TenantDataCleaner {
    /** 订单在清单中声明的 Data Resource 编码。 */
    static final String DATA_RESOURCE = "order";

    private final OrderMapper orderMapper;
    private final EventPublisher eventPublisher;
    private final DataPermission dataPermission;

    public OrderService(OrderMapper orderMapper, EventPublisher eventPublisher, DataPermission dataPermission) {
        this.orderMapper = orderMapper;
        this.eventPublisher = eventPublisher;
        this.dataPermission = dataPermission;
    }

    /**
     * 订单与「订单已创建」事件同一事务提交，不会出现有订单无事件或有事件无订单。
     *
     * <p>成员创建的订单归属本人与其主部门；INSERT 不经 SQL 过滤，先校验新订单在自己的数据范围内，否则 403，
     * 避免创建出自己看不到的数据。系统操作（无认证上下文）创建的订单没有归属。
     */
    @Transactional
    public Order create(String title, BigDecimal amount) {
        var order = new Order();
        order.setTitle(title);
        order.setAmount(amount);
        dataPermission.currentSubject().ifPresent(subject -> {
            order.setOwnerId((UUID) subject.get("subject.membershipId"));
            order.setDepartmentPath((String) subject.get("subject.primaryDepartmentPath"));
        });
        dataPermission.check(DATA_RESOURCE, attributes(order));
        orderMapper.insert(order);
        eventPublisher.publish(OrderCreated.TOPIC, new OrderCreated(order.getId(), title, amount));
        // 创建时间由数据库生成，重新读取才能返回与之后查询一致的值
        return get(order.getId());
    }

    public List<Order> list(PageQuery query) {
        List<OrderSort> sorts = query.sorts().stream().map(OrderSort::from).toList();
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

    private static Map<String, Object> attributes(Order order) {
        var attributes = new HashMap<String, Object>();
        attributes.put("ownerId", order.getOwnerId());
        attributes.put("departmentPath", order.getDepartmentPath());
        attributes.put("amount", order.getAmount());
        return attributes;
    }

    private static ForgeException notFound(UUID id) {
        return ForgeException.withErrorMessage(
                OrderErrorCode.NOT_FOUND, "Order " + id + " not found in current tenant");
    }

    /** 冷静期结束后把本租户的订单标记删除。 */
    @Override
    public void clean(TenantDeleted event) {
        orderMapper.markTenantDeleted();
    }
}
