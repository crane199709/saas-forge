package io.saasforge.iam.tenant.controller;

import io.saasforge.iam.tenant.service.TenantService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 服务间接口沿用 Resource Manifest 上报令牌；网关拒绝 /internal/**。
@Tag(name = "内部租户目录")
@RestController
class InternalTenantController {
    private final TenantService tenants;

    InternalTenantController(TenantService tenants) {
        this.tenants = tenants;
    }

    @Operation(summary = "按状态分页列出未删除的租户 ID，按 ID 升序")
    @GetMapping("/internal/tenants")
    ApiResponse<List<UUID>> list(
            @RequestParam @NotEmpty
                    List<@Pattern(regexp = "pending|trial|active|suspended|expired|archived|deleted") String> status,
            PageQuery query) {
        return ApiResponse.page(
                tenants.idsByStatus(status, query.offset(), query.size()),
                query.toPageMeta(tenants.countByStatus(status)));
    }
}
