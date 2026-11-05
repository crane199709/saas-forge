package io.saasforge.iam.referencedata.controller;

import io.saasforge.iam.referencedata.model.TenantDictionary;
import io.saasforge.iam.referencedata.model.TenantParameter;
import io.saasforge.iam.referencedata.service.ReferenceDataService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

// 服务间接口，网关拒绝 /internal/**；业务服务以上报令牌调用，SDK 按租户缓存结果。
@Tag(name = "内部参考数据")
@RestController
class InternalReferenceDataController {
    private final ReferenceDataService referenceData;

    InternalReferenceDataController(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    // 字典返回全部服务的：业务服务可以引用其他服务的字典（「服务:编码」）。参数只返回调用方自己的，其他服务的设置与它无关。
    @Operation(summary = "某租户合并后的生效字典与调用方服务的参数")
    @GetMapping("/internal/reference-data/{tenantId}")
    ApiResponse<ReferenceDataResponse> referenceData(@PathVariable UUID tenantId, Authentication service) {
        return ApiResponse.ok(new ReferenceDataResponse(
                referenceData.dictionaries(tenantId), referenceData.parameters(tenantId, service.getName())));
    }

    record ReferenceDataResponse(List<TenantDictionary> dictionaries, List<TenantParameter> parameters) {}
}
