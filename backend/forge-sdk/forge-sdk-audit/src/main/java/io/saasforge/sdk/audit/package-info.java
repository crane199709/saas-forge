/**
 * Audit Event 与 {@code @AuditLog} 声明式业务操作审计（规格第 11 节）。
 *
 * <p>业务服务在方法上标注 {@link io.saasforge.sdk.audit.AuditLog} 即记录操作的成功与失败；登录与权限变更由 iam 经
 * {@link io.saasforge.sdk.audit.AuditRecorder} 产出。事件经 Outbox 异步投递到 {@link io.saasforge.sdk.audit.AuditEvent#TOPIC}，
 * 由 iam 写入按月分区的审计表；写入失败只记日志，不影响业务请求。
 */
package io.saasforge.sdk.audit;
