package io.saasforge.iam.role.controller;

import io.saasforge.iam.role.model.PlanImpactResponse;
import io.saasforge.iam.role.service.PlanImpactService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 服务间接口沿用 Resource Manifest 上报令牌；网关拒绝 /internal/**。权限码列表可能较长，用请求体传递，接口只读。
@Tag(name = "内部 Plan 影响")
@RestController
class InternalPlanImpactController {
    private final PlanImpactService impacts;

    InternalPlanImpactController(PlanImpactService impacts) {
        this.impacts = impacts;
    }

    @Operation(summary = "统计租户的成员数，以及失去给定 Permission Code 与 Sub-App 时受影响的 Membership 数量")
    @PostMapping("/internal/tenants/{tenantId}/plan-impact")
    ApiResponse<PlanImpactResponse> impact(@PathVariable UUID tenantId, @Valid @RequestBody PlanImpactRequest request) {
        return ApiResponse.ok(impacts.impact(tenantId, request.lostPermissions(), request.lostSubApps()));
    }

    record PlanImpactRequest(
            @NotNull List<@NotBlank String> lostPermissions,
            @NotNull List<@NotBlank String> lostSubApps) {}
}
