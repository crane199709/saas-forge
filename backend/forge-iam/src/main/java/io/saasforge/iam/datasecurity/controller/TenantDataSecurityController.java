package io.saasforge.iam.datasecurity.controller;

import io.saasforge.iam.datasecurity.model.FieldResponse;
import io.saasforge.iam.datasecurity.model.GradeResponse;
import io.saasforge.iam.datasecurity.model.MaskingPreviewRequest;
import io.saasforge.iam.datasecurity.model.RuleResponse;
import io.saasforge.iam.datasecurity.service.DataSecurityService;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.MaskingPreview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data-security")
class TenantDataSecurityController {
    private final DataSecurityService security;

    TenantDataSecurityController(DataSecurityService security) {
        this.security = security;
    }

    @GetMapping("/fields")
    ApiResponse<List<FieldResponse>> fields(@AuthenticationPrincipal Jwt jwt) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.ok(security.fields(actor));
        }
    }

    @GetMapping("/rules")
    ApiResponse<List<RuleResponse>> rules(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(security.tenantRules(TenantActor.of(jwt)));
    }

    @GetMapping("/grades")
    ApiResponse<List<GradeResponse>> grades(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(security.tenantGrades(TenantActor.of(jwt)));
    }

    @PutMapping("/fields/{service}/{path}/rule")
    ApiResponse<FieldResponse> override(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String service,
            @PathVariable String path,
            @Valid @RequestBody RuleSelectionRequest request) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.ok(security.override(actor, service, path, request.ruleCode()));
        }
    }

    @DeleteMapping("/fields/{service}/{path}/rule")
    ApiResponse<Void> reset(@AuthenticationPrincipal Jwt jwt, @PathVariable String service, @PathVariable String path) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            security.reset(actor, service, path);
            return ApiResponse.ok();
        }
    }

    @PostMapping("/preview")
    ApiResponse<MaskingPreview> preview(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MaskingPreviewRequest request) {
        return ApiResponse.ok(security.tenantPreview(TenantActor.of(jwt), request.ruleCode(), request.value()));
    }

    record RuleSelectionRequest(@NotBlank @Size(max = 64) String ruleCode) {}
}
