package io.saasforge.iam.referencedata.controller;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.referencedata.model.DictionaryHistoryDetail;
import io.saasforge.iam.referencedata.model.DictionaryHistoryDiff;
import io.saasforge.iam.referencedata.model.DictionaryHistoryMetadata;
import io.saasforge.iam.referencedata.model.TenantDictionary;
import io.saasforge.iam.referencedata.model.TenantDictionaryItem;
import io.saasforge.iam.referencedata.model.TenantParameter;
import io.saasforge.iam.referencedata.service.ReferenceDataService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "字典与参数")
@RestController
@RequestMapping("/reference-data")
class ReferenceDataController {
    private static final String ITEM = "/dictionaries/{service}/{dictionary}/items/{code}";

    private final ReferenceDataService referenceData;

    ReferenceDataController(ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    @Operation(summary = "当前租户合并后的全部字典，含停用与下线项；租户全部成员可读")
    @GetMapping("/dictionaries")
    ApiResponse<List<TenantDictionary>> dictionaries(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(referenceData.dictionaries(TenantActor.of(jwt).tenantId()));
    }

    @Operation(summary = "按版本倒序查看字典变更历史，需要独立历史查看权限")
    @GetMapping("/dictionaries/{service}/{dictionary}/history")
    ApiResponse<List<DictionaryHistoryMetadata>> history(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String service, @PathVariable String dictionary) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.ok(referenceData.dictionaryHistory(actor, service, dictionary));
        }
    }

    @Operation(summary = "查看历史覆盖快照与当前值、当前服务声明上下文")
    @GetMapping("/dictionaries/{service}/{dictionary}/history/{version}")
    ApiResponse<DictionaryHistoryDetail> historyVersion(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String service,
            @PathVariable String dictionary,
            @PathVariable long version) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.ok(referenceData.dictionaryHistoryVersion(actor, service, dictionary, version));
        }
    }

    @Operation(summary = "比较任意两版租户覆盖快照")
    @GetMapping("/dictionaries/{service}/{dictionary}/history/diff")
    ApiResponse<DictionaryHistoryDiff> historyDiff(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String service,
            @PathVariable String dictionary,
            @RequestParam long from,
            @RequestParam long to) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.ok(referenceData.dictionaryHistoryDiff(actor, service, dictionary, from, to));
        }
    }

    @Operation(summary = "在可扩展字典中新增自定义项，编码自动加 custom. 前缀")
    @PostMapping("/dictionaries/{service}/{dictionary}/items")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<TenantDictionaryItem> createItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String service,
            @PathVariable String dictionary,
            @Valid @RequestBody CreateItemRequest request) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.created(referenceData.createItem(
                    actor, service, dictionary, request.code(), request.name().strip()));
        }
    }

    @Operation(summary = "修改字典项的显示名与排序")
    @PutMapping(ITEM)
    ApiResponse<TenantDictionaryItem> updateItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String service,
            @PathVariable String dictionary,
            @PathVariable String code,
            @Valid @RequestBody UpdateItemRequest request) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.ok(referenceData.updateItem(
                    actor, service, dictionary, code, request.name().strip(), request.sort()));
        }
    }

    @Operation(summary = "停用字典项")
    @PostMapping(ITEM + "/disable")
    ApiResponse<TenantDictionaryItem> disableItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String service,
            @PathVariable String dictionary,
            @PathVariable String code) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.ok(referenceData.disableItem(actor, service, dictionary, code));
        }
    }

    @Operation(summary = "启用已停用的字典项")
    @PostMapping(ITEM + "/enable")
    ApiResponse<TenantDictionaryItem> enableItem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String service,
            @PathVariable String dictionary,
            @PathVariable String code) {
        TenantActor actor = TenantActor.of(jwt);
        try (var ignored = CurrentTenant.bind(actor.tenantId())) {
            return ApiResponse.ok(referenceData.enableItem(actor, service, dictionary, code));
        }
    }

    @Operation(summary = "列出租户可修改的参数及当前值、默认值与约束")
    @GetMapping("/parameters")
    ApiResponse<List<TenantParameter>> parameters(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(referenceData.editableParameters(TenantActor.of(jwt)));
    }

    @Operation(summary = "覆盖参数值")
    @PutMapping("/parameters/{service}/{code}")
    ApiResponse<TenantParameter> overrideParameter(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String service,
            @PathVariable String code,
            @Valid @RequestBody ParameterValueRequest request) {
        return ApiResponse.ok(referenceData.overrideParameter(TenantActor.of(jwt), service, code, request.value()));
    }

    @Operation(summary = "恢复参数默认值")
    @DeleteMapping("/parameters/{service}/{code}")
    ApiResponse<Void> resetParameter(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String service, @PathVariable String code) {
        referenceData.resetParameter(TenantActor.of(jwt), service, code);
        return ApiResponse.ok();
    }

    /**
     * @param code 租户填写的编码，不含 {@code custom.} 前缀；加上前缀后不超过 64 个字符
     * @param name 显示名
     */
    record CreateItemRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,56}")
            String code,

            @NotBlank @Size(max = 120) String name) {}

    record UpdateItemRequest(
            @NotBlank @Size(max = 120) String name, @NotNull Integer sort) {}

    /** @param value 覆盖值，JSON 写法同 Resource Manifest 中的默认值 */
    record ParameterValueRequest(@NotNull Object value) {}
}
