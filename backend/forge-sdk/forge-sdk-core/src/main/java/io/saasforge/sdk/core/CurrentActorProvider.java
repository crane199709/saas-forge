package io.saasforge.sdk.core;

import java.util.Optional;
import java.util.UUID;

/**
 * 当前写入的操作人，用于填充标准列 {@code created_by} 与 {@code updated_by}。
 *
 * <p>定义在 core 而不是 security，是为了让持久层（forge-sdk-tenant）读取操作人时不依赖安全模块：认证方式由 security 实现，
 * 持久层只依赖本接口。
 */
public interface CurrentActorProvider {
    /**
     * 返回当前请求已验证 JWT 的 {@code sub}：Tenant Realm 服务为 Identity id，Platform Realm 服务为 Operator id。
     *
     * <p>不能抛异常：MQ 消费者、定时任务、登录流程等没有认证上下文的写入照常进行，此时返回空，标准列记为 NULL（系统）。
     *
     * @return 当前操作人；没有认证上下文时为空
     */
    Optional<UUID> currentActor();
}
