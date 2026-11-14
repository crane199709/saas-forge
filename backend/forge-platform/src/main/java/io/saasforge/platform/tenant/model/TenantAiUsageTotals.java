package io.saasforge.platform.tenant.model;

public record TenantAiUsageTotals(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        long estimatedTokens,
        long turnCount,
        long toolCalls,
        long unknownUsageTurns) {}
