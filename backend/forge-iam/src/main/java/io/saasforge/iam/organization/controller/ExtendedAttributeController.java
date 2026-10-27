package io.saasforge.iam.organization.controller;

import io.saasforge.iam.organization.model.ExtendedAttributeRow;
import io.saasforge.iam.organization.model.TenantActor;
import io.saasforge.iam.organization.service.ExtendedAttributeService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "扩展属性")
@RestController
@RequestMapping("/org/extended-attributes")
class ExtendedAttributeController {
    private final ExtendedAttributeService attributes;

    ExtendedAttributeController(ExtendedAttributeService attributes) {
        this.attributes = attributes;
    }

    @Operation(summary = "列出当前租户的扩展属性定义")
    @GetMapping
    ApiResponse<List<ExtendedAttributeResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(attributes.list(TenantActor.of(jwt)).stream()
                .map(ExtendedAttributeResponse::from)
                .toList());
    }

    @Operation(summary = "新建扩展属性定义；编码与类型创建后不可修改")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<ExtendedAttributeResponse> create(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAttributeRequest request) {
        return ApiResponse.created(ExtendedAttributeResponse.from(attributes.create(
                TenantActor.of(jwt), request.code(), request.name().strip(), request.type(), request.required())));
    }

    @Operation(summary = "修改扩展属性的名称与是否必填")
    @PutMapping("/{id}")
    ApiResponse<ExtendedAttributeResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAttributeRequest request) {
        return ApiResponse.ok(ExtendedAttributeResponse.from(
                attributes.update(TenantActor.of(jwt), id, request.name().strip(), request.required())));
    }

    @Operation(summary = "删除扩展属性定义，并移除各成员的该属性值")
    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        attributes.delete(TenantActor.of(jwt), id);
        return ApiResponse.ok();
    }

    // 编码是主体属性名 subject.attributes.<code> 的后缀，规则与数据库 CHECK 一致。
    record CreateAttributeRequest(
            @NotBlank @Pattern(regexp = "^[a-z][A-Za-z0-9]{0,63}$")
            String code,

            @NotBlank @Size(max = 120) String name,

            @NotNull @Pattern(regexp = "^(string|number|boolean)$")
            String type,

            boolean required) {}

    record UpdateAttributeRequest(@NotBlank @Size(max = 120) String name, boolean required) {}

    record ExtendedAttributeResponse(UUID id, String code, String name, String type, boolean required) {
        static ExtendedAttributeResponse from(ExtendedAttributeRow row) {
            return new ExtendedAttributeResponse(row.id(), row.code(), row.name(), row.type(), row.required());
        }
    }
}
