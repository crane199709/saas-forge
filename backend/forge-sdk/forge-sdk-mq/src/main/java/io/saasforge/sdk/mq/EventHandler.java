package io.saasforge.sdk.mq;

/**
 * 事件订阅，声明为 Spring Bean 即生效。
 *
 * <p>SDK 在调用 {@link #handle(Object)} 前恢复发布方的租户上下文并开启事务，处理逻辑中的数据访问照常按租户隔离；
 * 同一事件 ID 在本服务的消费组内只处理一次，去重登记与处理逻辑同一事务提交。处理抛出异常时事务回滚，
 * 消息由 RocketMQ 稍后重投。
 *
 * @param <T> 事件类型
 */
public interface EventHandler<T> {
    /**
     * 订阅的 Topic。
     *
     * @return Topic 名称
     */
    String topic();

    /**
     * 事件类型，其简单类名即订阅的 Tag，须与发布方的事件类名一致。
     *
     * @return 用于反序列化消息体的类型
     */
    Class<T> eventType();

    /**
     * 处理一条事件。
     *
     * @param event 反序列化后的事件
     */
    void handle(T event);
}
