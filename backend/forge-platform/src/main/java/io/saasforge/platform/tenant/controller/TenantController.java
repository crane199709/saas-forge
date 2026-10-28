package io.saasforge.platform.tenant.controller;

import io.saasforge.platform.tenant.entity.Tenant;
import io.saasforge.platform.tenant.entity.TenantInitialization;
import io.saasforge.platform.tenant.service.TenantService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// TODO(#22): 平台侧权限接入后按 Operator 的功能权限校验；在此之前任一 Operator 均可管理租户。
@Tag(name = "租户")
@RestController
@RequestMapping("/tenants")
class TenantController {
    private final TenantService tenants;

    TenantController(TenantService tenants) {
        this.tenants = tenants;
    }

    @Operation(summary = "创建租户并开始 Provisioning：iam 邀请初始管理员，各服务初始化本服务数据")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<TenantDetailResponse> create(@Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant =
                tenants.create(request.name().strip(), request.adminContact().strip());
        return ApiResponse.created(detail(tenant));
    }

    @Operation(summary = "分页列出租户，最近创建的在前")
    @GetMapping
    ApiResponse<List<TenantResponse>> list(PageQuery query) {
        List<TenantResponse> items = tenants.page(query.offset(), query.size()).stream()
                .map(TenantResponse::from)
                .toList();
        return ApiResponse.page(items, query.toPageMeta(tenants.count()));
    }

    @Operation(summary = "读取租户及各服务的初始化状态")
    @GetMapping("/{id}")
    ApiResponse<TenantDetailResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(detail(tenants.get(id)));
    }

    @Operation(summary = "重试某个服务失败的初始化")
    @PostMapping("/{id}/initializations/{service}/retry")
    ApiResponse<InitializationResponse> retry(@PathVariable UUID id, @PathVariable String service) {
        return ApiResponse.ok(InitializationResponse.from(tenants.retry(id, service)));
    }

    private TenantDetailResponse detail(Tenant tenant) {
        return new TenantDetailResponse(
                TenantResponse.from(tenant),
                tenants.initializations(tenant.getId()).stream()
                        .map(InitializationResponse::from)
                        .toList());
    }

    // 与 iam 的验证码目标格式一致：含 @ 的是邮箱，否则是手机号。
    record CreateTenantRequest(
            @NotBlank @Size(max = 120) String name,

            @NotBlank @Size(max = 254) @Pattern(regexp = "^\\s*(?:[^@\\s]+@[^@\\s]+\\.[^@\\s]+|\\+?[0-9]{6,15})\\s*$")
            String adminContact) {}

    record TenantResponse(UUID id, String name, String status, String adminContact, OffsetDateTime createdAt) {
        static TenantResponse from(Tenant tenant) {
            return new TenantResponse(
                    tenant.getId(),
                    tenant.getName(),
                    tenant.getStatus(),
                    tenant.getAdminContact(),
                    tenant.getCreatedAt());
        }
    }

    record InitializationResponse(String service, String status, String error, OffsetDateTime updatedAt) {
        static InitializationResponse from(TenantInitialization initialization) {
            return new InitializationResponse(
                    initialization.getService(),
                    initialization.getStatus(),
                    initialization.getError(),
                    initialization.getUpdatedAt());
        }
    }

    record TenantDetailResponse(TenantResponse tenant, List<InitializationResponse> initializations) {}
}
