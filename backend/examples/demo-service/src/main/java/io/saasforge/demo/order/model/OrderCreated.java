package io.saasforge.demo.order.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 订单已创建事件的消息体，随创建订单的事务经 Outbox 发布。
 *
 * @param orderId 订单 ID
 * @param title 订单标题
 * @param amount 订单金额
 */
public record OrderCreated(UUID orderId, String title, BigDecimal amount) {
    /** 订单事件所在的 Topic。 */
    public static final String TOPIC = "demo-order";
}
