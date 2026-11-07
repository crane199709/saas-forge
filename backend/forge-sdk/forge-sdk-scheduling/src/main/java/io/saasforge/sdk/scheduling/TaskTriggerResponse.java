package io.saasforge.sdk.scheduling;

import java.util.UUID;

/**
 * 已受理的异步手动执行。
 * @param runId 本次 Task Run 的 ID，可在运行查询中跟踪
 */
public record TaskTriggerResponse(UUID runId) {}
