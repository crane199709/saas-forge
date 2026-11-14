package io.saasforge.platform.tenant.controller;

import io.saasforge.platform.tenant.model.TenantAiUsageResponse;
import io.saasforge.platform.tenant.service.TenantAiUsageService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tenants/{id}/ai-usage")
public class TenantAiUsageController {
    private final TenantAiUsageService usage;

    public TenantAiUsageController(TenantAiUsageService usage) {
        this.usage = usage;
    }

    @GetMapping
    @RequiresPermission("platform:tenant:read")
    public ApiResponse<TenantAiUsageResponse> get(@PathVariable UUID id, @RequestParam(required = false) String month) {
        return ApiResponse.ok(usage.read(id, month));
    }
}
