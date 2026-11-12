package io.saasforge.iam.datasecurity.controller;

import io.saasforge.iam.datasecurity.model.GradeResponse;
import io.saasforge.iam.datasecurity.model.MaskingPreviewRequest;
import io.saasforge.iam.datasecurity.model.RuleResponse;
import io.saasforge.iam.datasecurity.service.DataSecurityService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.MaskingPreview;
import io.saasforge.sdk.security.MaskingRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/platform/data-security")
class PlatformDataSecurityController {
    private final DataSecurityService security;

    PlatformDataSecurityController(DataSecurityService security) {
        this.security = security;
    }

    @GetMapping("/rules")
    ApiResponse<List<RuleResponse>> rules(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(security.platformRules(actor(jwt)));
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RuleResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRuleRequest request) {
        return ApiResponse.created(
                security.createRule(actor(jwt), request.code(), request.name().strip(), request.rule()));
    }

    @PutMapping("/rules/{code}")
    ApiResponse<RuleResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String code,
            @Valid @RequestBody UpdateRuleRequest request) {
        return ApiResponse.ok(
                security.updateRule(actor(jwt), code, request.name().strip(), request.rule()));
    }

    @DeleteMapping("/rules/{code}")
    ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
        security.deleteRule(actor(jwt), code);
        return ApiResponse.ok();
    }

    @PostMapping("/preview")
    ApiResponse<MaskingPreview> preview(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MaskingPreviewRequest request) {
        return ApiResponse.ok(security.platformPreview(actor(jwt), request.ruleCode(), request.value()));
    }

    @PutMapping("/grades/{grade}")
    ApiResponse<GradeResponse> grade(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String grade,
            @Valid @RequestBody GradeDefaultRequest request) {
        return ApiResponse.ok(security.updateGrade(actor(jwt), grade, request.defaultRule()));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping("/grades")
    ApiResponse<List<GradeResponse>> grades(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(security.platformGrades(UUID.fromString(jwt.getSubject())));
    }

    record CreateRuleRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
            String code,

            @NotBlank @Size(max = 120) String name,
            @NotNull @Valid MaskingRule rule) {}

    record UpdateRuleRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull @Valid MaskingRule rule) {}

    record GradeDefaultRequest(@NotBlank @Size(max = 64) String defaultRule) {}
}
