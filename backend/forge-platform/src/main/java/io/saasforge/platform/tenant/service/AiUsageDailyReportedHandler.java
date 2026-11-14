package io.saasforge.platform.tenant.service;

import io.saasforge.sdk.mq.EventHandler;
import org.springframework.stereotype.Service;

@Service
public class AiUsageDailyReportedHandler implements EventHandler<AiUsageDailyReported> {
    private final TenantAiUsageService usage;

    public AiUsageDailyReportedHandler(TenantAiUsageService usage) {
        this.usage = usage;
    }

    public String topic() {
        return "forge-tenant";
    }

    public Class<AiUsageDailyReported> eventType() {
        return AiUsageDailyReported.class;
    }

    public void handle(AiUsageDailyReported event) {
        usage.accept(event);
    }
}
