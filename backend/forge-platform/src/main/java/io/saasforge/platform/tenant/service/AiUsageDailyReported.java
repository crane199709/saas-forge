package io.saasforge.platform.tenant.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 与外部 AI 服务共享 JSON 契约，不依赖其实现。 */
public record AiUsageDailyReported(
        UUID tenantId,
        LocalDate usageDate,
        String businessZone,
        long version,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long turnCount,
        long toolCalls,
        long unknownUsageTurns,
        long estimatedTokens,
        OffsetDateTime reportedAt) {}
