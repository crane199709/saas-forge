package io.saasforge.iam.role.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.role.model.ConditionAttribute;
import io.saasforge.iam.role.model.ConditionOptions;
import io.saasforge.iam.role.model.DataPolicy;
import io.saasforge.iam.role.model.DataResourceOption;
import io.saasforge.iam.role.model.MemberRoles;
import io.saasforge.iam.role.model.PermissionTreeResponse;
import io.saasforge.iam.role.model.PolicyPreview;
import io.saasforge.iam.role.model.RoleDataPolicyImpactResponse;
import io.saasforge.iam.role.model.RoleDetail;
import io.saasforge.iam.role.model.RolePermissionImpactResponse;
import io.saasforge.iam.role.service.PermissionTreeService;
import io.saasforge.iam.role.service.RoleManagementService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag(name = "角色")
@RestController
@RequestMapping("/roles")
class RoleController {
    private final RoleManagementService roles;
    private final JsonMapper json;
    private final PermissionTreeService permissionTrees;

    RoleController(RoleManagementService roles, JsonMapper json, PermissionTreeService permissionTrees) {
        this.permissionTrees = permissionTrees;
        this.roles = roles;
        this.json = json;
    }

    @Operation(summary = "列出当前租户的角色及其授权")
    @GetMapping
    ApiResponse<List<RoleResponse>> list(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean deleted) {
        return ApiResponse.ok(roles.list(TenantActor.of(jwt), deleted).stream()
                .map(role -> RoleResponse.from(role, this::toBody))
                .toList());
    }

    @Operation(summary = "当前租户可授予的菜单授权树")
    @GetMapping("/permission-tree")
    ApiResponse<PermissionTreeResponse> permissionTree(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(permissionTrees.of(TenantActor.of(jwt)));
    }

    @Operation(summary = "可授予的有效 Permission Code，按服务与分组排序")
    @GetMapping("/permissions")
    ApiResponse<List<PermissionResponse>> permissions(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(roles.grantable(TenantActor.of(jwt)).stream()
                .map(PermissionResponse::from)
                .toList());
    }

    @Operation(summary = "可配置 Data Policy 的 Data Resource，及其可用的 Data Scope 预设与自定义条件可引用的资源属性")
    @GetMapping("/data-resources")
    ApiResponse<List<DataResourceResponse>> dataResources(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(roles.dataResources(TenantActor.of(jwt)).stream()
                .map(DataResourceResponse::from)
                .toList());
    }

    @Operation(summary = "自定义条件可引用的主体属性（含扩展属性）与环境属性，以及部门、岗位取值")
    @GetMapping("/condition-options")
    ApiResponse<ConditionOptions> conditionOptions(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(roles.conditionOptions(TenantActor.of(jwt)));
    }

    @Operation(summary = "预览 Data Policy 对指定成员在样例数据上的命中情况，不保存")
    @PostMapping("/data-policies/preview")
    ApiResponse<PolicyPreview> preview(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PreviewRequest request) {
        return ApiResponse.ok(roles.preview(
                TenantActor.of(jwt), toPolicy(request.policy()), request.membershipId(), request.samples()));
    }

    @Operation(summary = "新建自定义角色，同时授予权限码并设置 Data Policy")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RoleResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.created(RoleResponse.from(
                roles.create(
                        TenantActor.of(jwt),
                        request.code(),
                        request.name().strip(),
                        request.permissions(),
                        toPolicies(request.dataPolicies())),
                this::toBody));
    }

    @Operation(summary = "自定义角色改名；预置角色不可修改")
    @PutMapping("/{id}")
    ApiResponse<Void> rename(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RenameRoleRequest request) {
        roles.rename(TenantActor.of(jwt), id, request.name().strip());
        return ApiResponse.ok();
    }

    @Operation(summary = "整体替换自定义角色的权限码授权；持有该角色的成员下一次请求起按新权限处理")
    @PutMapping("/{id}/permissions")
    ApiResponse<Void> replacePermissions(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody PermissionsRequest request) {
        roles.replacePermissions(TenantActor.of(jwt), id, request.permissions());
        return ApiResponse.ok();
    }

    @Operation(summary = "整体替换自定义角色上的 Data Policy（每个 Data Resource 选一个 Data Scope）；未配置的资源默认不可见")
    @PutMapping("/{id}/data-policies")
    ApiResponse<Void> replaceDataPolicies(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody DataPoliciesRequest request) {
        roles.replaceDataPolicies(TenantActor.of(jwt), id, toPolicies(request.policies()));
        return ApiResponse.ok();
    }

    @Operation(summary = "预览替换权限码的净影响，不保存：经其他角色仍持有或本来超出 Plan 的权限码不算失去，只统计状态正常的成员")
    @PostMapping("/{id}/permissions/impact")
    ApiResponse<RolePermissionImpactResponse> permissionImpact(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody PermissionsRequest request) {
        return ApiResponse.ok(roles.previewPermissions(TenantActor.of(jwt), id, request.permissions()));
    }

    @Operation(summary = "预览替换 Data Policy 影响的成员，不保存：只返回持有该角色的状态正常的成员，不判断具体数据行的变化")
    @PostMapping("/{id}/data-policies/impact")
    ApiResponse<RoleDataPolicyImpactResponse> dataPolicyImpact(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody DataPoliciesRequest request) {
        return ApiResponse.ok(roles.previewDataPolicies(TenantActor.of(jwt), id, toPolicies(request.policies())));
    }

    @Operation(summary = "删除自定义角色；预置角色或仍有成员持有时拒绝")
    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        roles.delete(TenantActor.of(jwt), id);
        return ApiResponse.ok();
    }

    @Operation(summary = "恢复已删除的自定义角色")
    @PostMapping("/{id}/restore")
    ApiResponse<Void> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        roles.restore(TenantActor.of(jwt), id);
        return ApiResponse.ok();
    }

    @Operation(summary = "分页列出当前租户的成员及其角色")
    @GetMapping("/memberships")
    ApiResponse<List<MemberRolesResponse>> members(@AuthenticationPrincipal Jwt jwt, PageQuery query) {
        TenantActor actor = TenantActor.of(jwt);
        return ApiResponse.page(
                roles.page(actor, query.offset(), query.size()).stream()
                        .map(MemberRolesResponse::from)
                        .toList(),
                query.toPageMeta(roles.count(actor)));
    }

    @Operation(summary = "整体替换成员的角色（不含 Owner）；该成员下一次请求起按新权限处理")
    @PutMapping("/memberships/{membershipId}")
    ApiResponse<Void> assign(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID membershipId, @RequestBody AssignRequest request) {
        roles.assign(TenantActor.of(jwt), membershipId, request.roleIds());
        return ApiResponse.ok();
    }

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

    private List<DataPolicy> toPolicies(List<DataPolicyBody> bodies) {
        return bodies.stream().map(this::toPolicy).toList();
    }

    private DataPolicy toPolicy(DataPolicyBody body) {
        JsonNode condition = body.condition();
        return new DataPolicy(
                body.resource(),
                body.scope(),
                condition == null || condition.isNull() ? null : json.writeValueAsString(condition));
    }

    private DataPolicyBody toBody(DataPolicy policy) {
        return new DataPolicyBody(
                policy.resource(),
                policy.scope(),
                policy.condition() == null ? null : json.readTree(policy.condition()));
    }

    record RenameRoleRequest(@NotBlank @Size(max = 120) String name) {}

    record PermissionsRequest(List<String> permissions) {
        PermissionsRequest {
            permissions = permissions == null ? List.of() : permissions;
        }
    }

    record DataPoliciesRequest(List<DataPolicyBody> policies) {
        DataPoliciesRequest {
            policies = policies == null ? List.of() : policies;
        }
    }

    /**
     * @param scope {@code self department department_and_below all}，或自定义条件 {@code custom}
     * @param condition {@code custom} 时为 JSON 表达式树，如
     *     {@code {"and":[{"attr":"resource.amount","op":"gt","value":1000}]}}；预设时省略
     */
    record DataPolicyBody(
            String resource,
            String scope,
            @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode condition) {}

    /**
     * @param policy 待预览的策略，可以是尚未保存的自定义条件
     * @param membershipId 以该成员当前的主体属性求值
     * @param samples 样例数据，每条为资源属性编码到值；未给出的属性视为空
     */
    record PreviewRequest(
            @NotNull DataPolicyBody policy, @NotNull UUID membershipId, List<Map<String, Object>> samples) {
        PreviewRequest {
            samples = samples == null ? List.of() : samples;
        }
    }

    record AssignRequest(List<UUID> roleIds) {
        AssignRequest {
            roleIds = roleIds == null ? List.of() : roleIds;
        }
    }

    /**
     * @param builtin 预置角色不可修改或删除，其权限为全部有效权限码、数据范围为全部，{@code permissions} 与 {@code dataPolicies} 为空
     * @param owner Owner 角色不经角色分配授予
     */
    record RoleResponse(
            UUID id,
            String code,
            String name,
            boolean builtin,
            boolean owner,
            long memberCount,
            List<String> permissions,
            List<DataPolicyBody> dataPolicies,
            Instant updatedAt,
            UUID updatedBy) {
        static RoleResponse from(RoleDetail value, Function<DataPolicy, DataPolicyBody> body) {
            return new RoleResponse(
                    value.id(),
                    value.code(),
                    value.name(),
                    value.builtin(),
                    value.owner(),
                    value.memberCount(),
                    value.permissions(),
                    value.dataPolicies().stream().map(body).toList(),
                    value.updatedAt(),
                    value.updatedBy());
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

    record MemberRolesResponse(
            UUID membershipId, String account, String displayName, String status, List<UUID> roleIds) {
        static MemberRolesResponse from(MemberRoles value) {
            return new MemberRolesResponse(
                    value.membershipId(), value.account(), value.displayName(), value.status(), value.roleIds());
        }
    }
}
