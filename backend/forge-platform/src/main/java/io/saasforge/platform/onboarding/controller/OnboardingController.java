package io.saasforge.platform.onboarding.controller;

import io.saasforge.platform.onboarding.model.OnboardingDiagnostics;
import io.saasforge.platform.onboarding.service.OnboardingService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OnboardingController {
    private final OnboardingService diagnostics;

    OnboardingController(OnboardingService diagnostics) {
        this.diagnostics = diagnostics;
    }

    @Operation(summary = "读取服务接入的三级独立诊断证据")
    @RequiresPermission("platform:onboarding:read")
    @GetMapping("/onboarding")
    public ApiResponse<OnboardingDiagnostics> read() {
        return ApiResponse.ok(diagnostics.read());
    }
}
