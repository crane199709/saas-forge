package io.saasforge.sdk.scheduling;
/**
 * 服务声明的任务及最近运行；规则仅供查看，不允许 Operator 修改。
 * @param name 服务内唯一任务名
 * @param type 任务类型
 * @param cron 已解析的六段 cron；fixedDelay 任务为空
 * @param fixedDelay ISO-8601 Duration 字符串；cron 任务为空
 * @param zone cron 的 IANA 时区；fixedDelay 任务为空
 * @param latestRun 当前运行优先，否则最近结束运行；没有历史时为空
 */
public record TaskDefinitionResponse(
        String name, TaskRun.Type type, String cron, String fixedDelay, String zone, TaskRun latestRun) {}
