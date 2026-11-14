package io.saasforge.platform.tenant.model;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record TenantAiUsageDay(
        LocalDate usageDate,
        long version,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long turnCount,
        long toolCalls,
        long unknownUsageTurns,
        long estimatedTokens,
        OffsetDateTime reportedAt) {}
