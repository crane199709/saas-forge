package io.saasforge.demo.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.demo.notification.entity.OrderNotification;
import io.saasforge.demo.notification.mapper.OrderNotificationMapper;
import io.saasforge.demo.order.model.OrderCreated;
import io.saasforge.sdk.mq.EventHandler;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 演示订阅「订单已创建」：处理逻辑与普通业务代码一样不含租户条件，租户由 SDK 从消息头恢复。 */
@Service
public class OrderNotificationService implements EventHandler<OrderCreated> {
    private final OrderNotificationMapper notificationMapper;

    public OrderNotificationService(OrderNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Override
    public String topic() {
        return OrderCreated.TOPIC;
    }

    @Override
    public Class<OrderCreated> eventType() {
        return OrderCreated.class;
    }

    @Override
    public void handle(OrderCreated event) {
        var notification = new OrderNotification();
        notification.setOrderId(event.orderId());
        notification.setTitle("新订单：" + event.title());
        notificationMapper.insert(notification);
    }

    public List<OrderNotification> findByOrder(UUID orderId) {
        return notificationMapper.selectList(
                new LambdaQueryWrapper<OrderNotification>().eq(OrderNotification::getOrderId, orderId));
    }
}
