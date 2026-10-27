package io.saasforge.iam.organization.controller;

import io.saasforge.iam.organization.model.PositionRow;
import io.saasforge.iam.organization.model.TenantActor;
import io.saasforge.iam.organization.service.PositionService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@Tag(name = "岗位")
@RestController
@RequestMapping("/org/positions")
class PositionController {
    private final PositionService positions;

    PositionController(PositionService positions) {
        this.positions = positions;
    }

    @Operation(summary = "列出当前租户的岗位")
    @GetMapping
    ApiResponse<List<PositionResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(positions.list(TenantActor.of(jwt)).stream()
                .map(PositionResponse::from)
                .toList());
    }

    @Operation(summary = "新建岗位")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<PositionResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PositionRequest request) {
        return ApiResponse.created(PositionResponse.from(
                positions.create(TenantActor.of(jwt), request.name().strip())));
    }

    @Operation(summary = "岗位改名")
    @PutMapping("/{id}")
    ApiResponse<PositionResponse> rename(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PositionRequest request) {
        return ApiResponse.ok(PositionResponse.from(
                positions.rename(TenantActor.of(jwt), id, request.name().strip())));
    }

    @Operation(summary = "删除岗位；仍有成员担任时拒绝")
    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        positions.delete(TenantActor.of(jwt), id);
        return ApiResponse.ok();
    }

    record PositionRequest(@NotBlank @Size(max = 120) String name) {}

    record PositionResponse(UUID id, String name) {
        static PositionResponse from(PositionRow row) {
            return new PositionResponse(row.id(), row.name());
        }
    }
}
