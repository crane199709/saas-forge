package io.saasforge.demo.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.mapper.OrderMapper;
import io.saasforge.demo.order.model.OrderCreated;
import io.saasforge.demo.order.model.OrderSort;
import io.saasforge.sdk.abac.data.DataPermission;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.notification.NotificationClient;
import io.saasforge.sdk.notification.NotificationLink;
import io.saasforge.sdk.notification.NotificationTarget;
import io.saasforge.sdk.numbering.BusinessNumbers;
import io.saasforge.sdk.numbering.DateSegment;
import io.saasforge.sdk.numbering.NumberingRule;
import io.saasforge.sdk.provisioning.TenantDataCleaner;
import io.saasforge.sdk.provisioning.TenantDeleted;
import io.saasforge.sdk.security.TenantContext;
import io.saasforge.sdk.web.PageQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
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

    /** 订单编号 SO-yyyyMMdd-NNNN，每个业务日重新计数。 */
    static final NumberingRule NUMBERING = new NumberingRule("order", "SO", DateSegment.DAY, 4);

    private final OrderMapper orderMapper;
    private final EventPublisher eventPublisher;
    private final DataPermission dataPermission;
    private final BusinessNumbers businessNumbers;
    private final NotificationClient notifications;

    public OrderService(
            OrderMapper orderMapper,
            EventPublisher eventPublisher,
            DataPermission dataPermission,
            BusinessNumbers businessNumbers,
            NotificationClient notifications) {
        this.orderMapper = orderMapper;
        this.eventPublisher = eventPublisher;
        this.dataPermission = dataPermission;
        this.businessNumbers = businessNumbers;
        this.notifications = notifications;
    }

    /**
     * 订单与「订单已创建」事件同一事务提交，不会出现有订单无事件或有事件无订单。
     *
     * <p>成员创建的订单归属本人与其主部门；INSERT 不经 SQL 过滤，先校验新订单在自己的数据范围内，否则 403，
     * 避免创建出自己看不到的数据。系统操作（无认证上下文）创建的订单没有归属。
     *
     * <p>编号在校验通过后、写入前才取：取号会锁住当天的计数行直到事务结束，越晚取，同一天的并发下单排队越短；
     * 订单写入失败时编号随事务回滚，不留空号。
     */
    @Transactional
    public Order create(String title, BigDecimal amount, String customerSource) {
        var order = new Order();
        order.setTitle(title);
        order.setAmount(amount);
        order.setCustomerSource(customerSource);
        dataPermission.currentSubject().ifPresent(subject -> {
            order.setOwnerId((UUID) subject.get("subject.membershipId"));
            order.setDepartmentPath((String) subject.get("subject.primaryDepartmentPath"));
        });
        dataPermission.check(DATA_RESOURCE, attributes(order));
        order.setNumber(businessNumbers.next(NUMBERING));
        orderMapper.insert(order);
        eventPublisher.publish(OrderCreated.TOPIC, new OrderCreated(order.getId(), title, amount));
        if (order.getOwnerId() != null) {
            notifications.openTodo(
                    "order:" + order.getId(),
                    NotificationTarget.memberships(List.of(order.getOwnerId())),
                    "处理订单 " + order.getNumber(),
                    title,
                    new NotificationLink("demo", "/overview"),
                    Instant.now());
            notifications.send(
                    NotificationTarget.memberships(List.of(order.getOwnerId())),
                    "订单已创建",
                    "订单 " + order.getNumber() + " 已创建",
                    new NotificationLink("demo", "/overview"));
        }
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

    public Order update(UUID id, String title, BigDecimal amount, String customerSource) {
        var order = new Order();
        order.setId(id);
        order.setTitle(title);
        order.setAmount(amount);
        order.setCustomerSource(customerSource);
        if (orderMapper.updateById(order) == 0) {
            throw notFound(id);
        }
        return get(id);
    }

    /** 订单状态与关闭待办事件同一事务提交，重复关单不产生新状态或刷新审计。 */
    @Transactional
    public Order close(UUID id) {
        get(id);
        if (orderMapper.closeUnpaid(id, TenantContext.current().identityId()) > 0) {
            notifications.closeTodo("order:" + id, Instant.now());
        }
        return get(id);
    }

    /** 超时关单和待办关闭同事务，条件更新排除并发已完成的关单。 */
    @Transactional
    public void closeExpired(OffsetDateTime cutoff) {
        for (UUID id : orderMapper.expiredUnpaid(cutoff)) {
            if (orderMapper.closeUnpaid(id, null) > 0) {
                notifications.closeTodo("order:" + id, Instant.now());
            }
        }
    }

    @Transactional
    public void delete(UUID id) {
        if (orderMapper.deleteById(id) == 0) {
            throw notFound(id);
        }
        notifications.closeTodo("order:" + id, Instant.now());
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
