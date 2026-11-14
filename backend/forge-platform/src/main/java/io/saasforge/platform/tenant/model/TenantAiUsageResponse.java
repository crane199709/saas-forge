package io.saasforge.platform.tenant.model;

import java.util.List;

public record TenantAiUsageResponse(
        String month,
        String businessZone,
        boolean received,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long totalTokens,
        long estimatedTokens,
        long turnCount,
        long toolCalls,
        long unknownUsageTurns,
        List<TenantAiUsageDay> days) {}
