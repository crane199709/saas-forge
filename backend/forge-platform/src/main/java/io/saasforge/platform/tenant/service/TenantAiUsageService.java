package io.saasforge.platform.tenant.service;

import io.saasforge.platform.tenant.mapper.TenantAiUsageMapper;
import io.saasforge.platform.tenant.model.*;
import io.saasforge.sdk.core.BusinessZoneProperties;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import java.time.*;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(BusinessZoneProperties.class)
public class TenantAiUsageService {
    private final TenantService tenants;
    private final TenantAiUsageMapper mapper;
    private final ZoneId zone;

    public TenantAiUsageService(TenantService tenants, TenantAiUsageMapper mapper, BusinessZoneProperties properties) {
        this.tenants = tenants;
        this.mapper = mapper;
        this.zone = properties.require("Tenant AI usage");
    }

    @Transactional
    public void accept(AiUsageDailyReported e) {
        if (e.tenantId() == null
                || !CurrentTenant.require().equals(e.tenantId())
                || e.usageDate() == null
                || e.reportedAt() == null
                || !zone.getId().equals(e.businessZone())
                || e.version() <= 0
                || e.inputTokens() < 0
                || e.outputTokens() < 0
                || e.cachedInputTokens() < 0
                || e.cachedInputTokens() > e.inputTokens()
                || e.turnCount() < 0
                || e.toolCalls() < 0
                || e.unknownUsageTurns() < 0
                || e.unknownUsageTurns() > e.turnCount()
                || e.estimatedTokens() < 0) {
            throw new ForgeException(TenantErrorCode.INVALID_AI_USAGE);
        }
        if (tenants.lockForAiUsage(e.tenantId()).getPurgedAt() != null) {
            // 最终删除前的在途快照可以晚到；确认消费但不重建已清理投影。
            return;
        }
        mapper.apply(UuidV7.generate(), e);
    }

    @Transactional(readOnly = true)
    public TenantAiUsageResponse read(UUID tenantId, String value) {
        tenants.get(tenantId);
        YearMonth month;
        try {
            month = value == null ? YearMonth.now(zone) : YearMonth.parse(value);
        } catch (DateTimeException e) {
            throw new ForgeException(TenantErrorCode.INVALID_AI_USAGE);
        }
        var from = month.atDay(1);
        var to = month.plusMonths(1).atDay(1);
        var t = mapper.totals(tenantId, from, to);
        var days = mapper.days(tenantId, from, to);
        return new TenantAiUsageResponse(
                month.toString(),
                zone.getId(),
                !days.isEmpty(),
                t.inputTokens(),
                t.outputTokens(),
                t.cachedInputTokens(),
                t.inputTokens() + t.outputTokens(),
                t.estimatedTokens(),
                t.turnCount(),
                t.toolCalls(),
                t.unknownUsageTurns(),
                days);
    }
}
