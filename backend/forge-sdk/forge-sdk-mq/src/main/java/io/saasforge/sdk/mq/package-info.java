/**
 * RocketMQ 封装、Outbox、幂等消费与租户上下文透传（ADR-0008、ADR-0012）。
 *
 * <p>发布方经 {@link io.saasforge.sdk.mq.EventPublisher} 在业务事务中写入 Outbox，SDK 投递器在提交后发送并按退避重试；
 * 订阅方声明 {@link io.saasforge.sdk.mq.EventHandler} Bean，SDK 恢复租户上下文并按事件 ID 去重后调用。
 * 投递保证至少一次，处理保证同一消费组内至多一次。
 *
 * <p>消费失败达到 {@code forge.mq.max-delivery-attempts}（默认 16）时，在独立事务中保存 Dead Event 后确认消息；
 * 捕获失败继续重投。服务须创建 {@code forge_dead_event}，并保证消费组重试次数不低于该上限。
 * {@code forge.mq.dead.events} 统计新增死信（标签 {@code consumer.group}），
 * {@code forge.mq.outbox.pending} 显示全部未发送事件（含退避中的事件）。
 */
package io.saasforge.sdk.mq;
