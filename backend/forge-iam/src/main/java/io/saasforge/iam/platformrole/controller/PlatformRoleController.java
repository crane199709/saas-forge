package io.saasforge.iam.platformrole.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.saasforge.iam.operator.model.OperatorGrants;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.platformrole.model.PlatformRoleDetail;
import io.saasforge.iam.platformrole.service.PlatformRoleManagementService;
import io.saasforge.iam.platformrole.service.PlatformRoleService;
import io.saasforge.iam.role.model.ConditionAttribute;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.DataResourceOption;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 只接受 Platform Realm Token（网关按 /iam/platform/** 校验，iam 以 Platform Realm 的解码器验签），操作人取自 Token 的 sub。
@Tag(name = "平台角色")
@RestController
class PlatformRoleController {
    private final PlatformRoleManagementService roles;
    private final PlatformRoleService grants;
    private final JsonMapper json;

    PlatformRoleController(PlatformRoleManagementService roles, PlatformRoleService grants, JsonMapper json) {
        this.roles = roles;
        this.grants = grants;
        this.json = json;
    }

    @Operation(summary = "当前 Operator 的平台角色与有效权限码，platform-console 据此显示功能")
    @GetMapping("/platform/access")
    ApiResponse<AccessResponse> access(@AuthenticationPrincipal Jwt jwt) {
        OperatorGrants current = grants.accessOf(actor(jwt));
        return ApiResponse.ok(new AccessResponse(
                current.roles().stream().sorted().toList(),
                current.permissions().stream().sorted().toList()));
    }

    @Operation(summary = "列出平台角色及其授权与 Data Policy")
    @GetMapping("/platform/roles")
    ApiResponse<List<RoleResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(roles.list(actor(jwt)).stream()
                .map(role -> RoleResponse.from(role, this::toBody))
                .toList());
    }

    @Operation(summary = "可授予平台角色的有效权限码（只含 Platform Realm 服务申报的），按服务与分组排序")
    @GetMapping("/platform/roles/permissions")
    ApiResponse<List<PermissionResponse>> permissions(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(roles.grantable(actor(jwt)).stream()
                .map(PermissionResponse::from)
                .toList());
    }

    @Operation(summary = "可配置平台 Data Policy 的 Data Resource，及其可用的 Data Scope 预设与资源属性")
    @GetMapping("/platform/roles/data-resources")
    ApiResponse<List<DataResourceResponse>> dataResources(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(roles.dataResources(actor(jwt)).stream()
                .map(DataResourceResponse::from)
                .toList());
    }

    @Operation(summary = "新建平台角色，同时授予权限码并设置 Data Policy")
    @PostMapping("/platform/roles")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RoleResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.created(RoleResponse.from(
                roles.create(
                        actor(jwt),
                        request.code(),
                        request.name().strip(),
                        request.permissions(),
                        toPolicies(request.dataPolicies())),
                this::toBody));
    }

    @Operation(summary = "修改平台角色的名称、授权与 Data Policy（整体替换）；持有者下一次请求起按新权限处理，超级管理员不可修改")
    @PutMapping("/platform/roles/{id}")
    ApiResponse<Void> update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
        roles.update(actor(jwt), id, request.name().strip(), request.permissions(), toPolicies(request.dataPolicies()));
        return ApiResponse.ok();
    }

    @Operation(summary = "删除平台角色；超级管理员或仍有 Operator 持有时拒绝")
    @DeleteMapping("/platform/roles/{id}")
    ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        roles.delete(actor(jwt), id);
        return ApiResponse.ok();
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private List<DataPolicy> toPolicies(List<DataPolicyBody> bodies) {
        return bodies.stream()
                .map(body -> new DataPolicy(
                        body.resource(),
                        body.scope(),
                        body.condition() == null || body.condition().isNull()
                                ? null
                                : json.writeValueAsString(body.condition())))
                .toList();
    }

    private DataPolicyBody toBody(DataPolicy policy) {
        return new DataPolicyBody(
                policy.resource(),
                policy.scope(),
                policy.condition() == null ? null : json.readTree(policy.condition()));
    }

    /** @param roles 平台角色编码，含超级管理员时为 {@code super-admin} */
    record AccessResponse(List<String> roles, List<String> permissions) {}

    /** @param code 创建后不可修改，小写字母、数字与连字符 */
    record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}")
            String code,

            @NotBlank @Size(max = 120) String name,
            List<String> permissions,
            List<DataPolicyBody> dataPolicies) {
        CreateRoleRequest {
            permissions = permissions == null ? List.of() : permissions;
            dataPolicies = dataPolicies == null ? List.of() : dataPolicies;
        }
    }

    record UpdateRoleRequest(
            @NotBlank @Size(max = 120) String name, List<String> permissions, List<DataPolicyBody> dataPolicies) {
        UpdateRoleRequest {
            permissions = permissions == null ? List.of() : permissions;
            dataPolicies = dataPolicies == null ? List.of() : dataPolicies;
        }
    }

    /**
     * @param scope {@code self all} 等该资源可用的预设，或自定义条件 {@code custom}
     * @param condition {@code custom} 时为 JSON 表达式树，可引用 Operator 的负责区域，如
     *     {@code {"attr":"resource.region","op":"eq","value":{"ref":"subject.attributes.region"}}}；预设时省略
     */
    record DataPolicyBody(
            String resource,
            String scope,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode condition) {}

    /**
     * @param builtin 超级管理员不可修改或删除，其权限为全部有效平台权限码、数据范围为全部，{@code permissions} 与
     *     {@code dataPolicies} 为空
     */
    record RoleResponse(
            UUID id,
            String code,
            String name,
            boolean builtin,
            long operatorCount,
            List<String> permissions,
            List<DataPolicyBody> dataPolicies) {
        static RoleResponse from(PlatformRoleDetail value, Function<DataPolicy, DataPolicyBody> body) {
            return new RoleResponse(
                    value.id(),
                    value.code(),
                    value.name(),
                    value.builtin(),
                    value.operatorCount(),
                    value.permissions(),
                    value.dataPolicies().stream().map(body).toList());
        }
    }

    record DataResourceResponse(
            String code, String name, String service, List<String> scopes, List<ConditionAttribute> attributes) {
        static DataResourceResponse from(DataResourceOption value) {
            return new DataResourceResponse(
                    value.code(), value.name(), value.service(), value.scopes(), value.attributes());
        }
    }

    record PermissionResponse(String code, String name, String service, String group) {
        static PermissionResponse from(PermissionRow row) {
            return new PermissionResponse(row.code(), row.name(), row.service(), row.group());
        }
    }
}
