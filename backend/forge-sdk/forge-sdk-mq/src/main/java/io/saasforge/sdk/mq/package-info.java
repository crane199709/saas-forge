/**
 * RocketMQ 封装、Outbox、幂等消费与租户上下文透传（ADR-0008、ADR-0012）。
 *
 * <p>发布方经 {@link io.saasforge.sdk.mq.EventPublisher} 在业务事务中写入 Outbox，SDK 投递器在提交后发送并按退避重试；
 * 订阅方声明 {@link io.saasforge.sdk.mq.EventHandler} Bean，SDK 恢复租户上下文并按事件 ID 去重后调用。
 * 投递保证至少一次，处理保证同一消费组内至多一次。
 */
package io.saasforge.sdk.mq;
