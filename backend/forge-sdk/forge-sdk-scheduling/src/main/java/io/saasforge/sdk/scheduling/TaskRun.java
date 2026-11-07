package io.saasforge.sdk.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 一次实际执行的 Task Run；不含内部锁令牌或异常消息。
 *
 * @param id 运行 ID
 * @param name 服务内唯一任务名
 * @param type 任务类型
 * @param trigger 触发方式
 * @param operatorId 手动触发的 Operator；定时触发时为空
 * @param targetTenantId 单租户重跑目标；整轮运行时为空
 * @param status 运行状态
 * @param startedAt 开始时间
 * @param finishedAt 结束时间；运行中时为空
 * @param successCount 成功租户数；Global Task 为零
 * @param failureCount 失败租户总数；Global Task 为零
 * @param failedTenants 前 100 个失败租户
 * @param errorSummary 只含异常类型与 ForgeException 错误码的摘要
 */
public record TaskRun(
        UUID id,
        String name,
        Type type,
        Trigger trigger,
        UUID operatorId,
        UUID targetTenantId,
        Status status,
        Instant startedAt,
        Instant finishedAt,
        int successCount,
        int failureCount,
        List<FailedTenant> failedTenants,
        String errorSummary) {
    /** 复制失败租户列表，避免查询结果被调用方修改。 */
    public TaskRun {
        failedTenants = List.copyOf(failedTenants);
    }

    /** 任务声明类型。 */
    public enum Type {
        /** 全局任务。 */
        GLOBAL,
        /** 按租户任务。 */
        TENANT
    }

    /** Task Run 的触发来源。 */
    public enum Trigger {
        /** 定时触发。 */
        SCHEDULED,
        /** Operator 手动触发。 */
        MANUAL
    }

    /** Task Run 的可观察状态。 */
    public enum Status {
        /** 正在运行。 */
        RUNNING,
        /** 运行成功。 */
        SUCCEEDED,
        /** 部分租户失败。 */
        PARTIALLY_FAILED,
        /** 整体失败。 */
        FAILED,
        /** 进程退出或失去锁，未正常结束。 */
        INTERRUPTED
    }

    /**
     * 失败租户，不包含任务的业务数据。
     *
     * @param tenantId 失败租户 ID
     * @param errorSummary 只含异常类型与 ForgeException 错误码的摘要
     */
    public record FailedTenant(UUID tenantId, String errorSummary) {}
}
