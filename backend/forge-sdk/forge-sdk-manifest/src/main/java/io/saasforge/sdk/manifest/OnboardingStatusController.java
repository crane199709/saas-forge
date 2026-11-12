package io.saasforge.sdk.manifest;

import io.saasforge.sdk.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运维令牌认证的本实例只读状态端点；网关拒绝 /internal/。 */
@RestController
public final class OnboardingStatusController {
    private final ManifestReporting reporting;
    /** 绑定本实例生命周期。 */
    public OnboardingStatusController(ManifestReporting reporting) {
        this.reporting = reporting;
    }
    /** 返回上报状态，Service Credential 不能用于读取。 */
    @GetMapping("/internal/onboarding-status")
    public ApiResponse<OnboardingStatus> status() {
        return ApiResponse.ok(reporting.status());
    }
}
