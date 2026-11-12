package io.saasforge.iam.datasecurity.controller;

import io.saasforge.iam.datasecurity.model.GradeResponse;
import io.saasforge.iam.datasecurity.model.RuleResponse;
import io.saasforge.iam.datasecurity.service.DataSecurityService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.MaskingPreview;
import jakarta.validation.Valid;
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
    ApiResponse<RuleResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DataSecurityRequests.CreateRule request) {
        return ApiResponse.created(
                security.createRule(actor(jwt), request.code(), request.name().strip(), request.rule()));
    }

    @PutMapping("/rules/{code}")
    ApiResponse<RuleResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String code,
            @Valid @RequestBody DataSecurityRequests.UpdateRule request) {
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
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DataSecurityRequests.Preview request) {
        return ApiResponse.ok(security.platformPreview(actor(jwt), request.ruleCode(), request.value()));
    }

    @PutMapping("/grades/{grade}")
    ApiResponse<GradeResponse> grade(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String grade,
            @Valid @RequestBody DataSecurityRequests.GradeDefault request) {
        return ApiResponse.ok(security.updateGrade(actor(jwt), grade, request.defaultRule()));
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping("/grades")
    ApiResponse<List<GradeResponse>> grades(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(security.platformGrades(UUID.fromString(jwt.getSubject())));
    }
}
