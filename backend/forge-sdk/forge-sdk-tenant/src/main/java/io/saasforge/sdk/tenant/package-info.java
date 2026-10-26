/**
 * MyBatis-Plus 租户拦截、RLS 会话变量、带租户前缀的 Redis 访问与异步任务的租户传递。
 *
 * <p>租户一律取自 {@link io.saasforge.sdk.core.CurrentTenant}；业务表须带 {@code tenant_id} 列并启用
 * {@code ENABLE} 与 {@code FORCE ROW LEVEL SECURITY}，策略读取会话变量 {@code app.tenant_id}（ADR-0001）。
 */
package io.saasforge.sdk.tenant;
