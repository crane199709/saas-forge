package io.saasforge.iam.role.controller;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.permission.model.PermissionRow;
import io.saasforge.iam.role.model.MemberRoles;
import io.saasforge.iam.role.model.RoleDetail;
import io.saasforge.iam.role.service.RoleManagementService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@Tag(name = "角色")
@RestController
@RequestMapping("/roles")
class RoleController {
    private final RoleManagementService roles;

    RoleController(RoleManagementService roles) {
        this.roles = roles;
    }

    @Operation(summary = "列出当前租户的角色及其授权")
    @GetMapping
    ApiResponse<List<RoleResponse>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(
                roles.list(TenantActor.of(jwt)).stream().map(RoleResponse::from).toList());
    }

    @Operation(summary = "可授予的有效 Permission Code，按服务与分组排序")
    @GetMapping("/permissions")
    ApiResponse<List<PermissionResponse>> permissions(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(roles.grantable(TenantActor.of(jwt)).stream()
                .map(PermissionResponse::from)
                .toList());
    }

    @Operation(summary = "新建自定义角色并授予权限码")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RoleResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.created(RoleResponse.from(
                roles.create(TenantActor.of(jwt), request.code(), request.name().strip(), request.permissions())));
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

    @Operation(summary = "删除自定义角色；预置角色或仍有成员持有时拒绝")
    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        roles.delete(TenantActor.of(jwt), id);
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
            List<String> permissions) {
        CreateRoleRequest {
            permissions = permissions == null ? List.of() : permissions;
        }
    }

    record RenameRoleRequest(@NotBlank @Size(max = 120) String name) {}

    record PermissionsRequest(List<String> permissions) {
        PermissionsRequest {
            permissions = permissions == null ? List.of() : permissions;
        }
    }

    record AssignRequest(List<UUID> roleIds) {
        AssignRequest {
            roleIds = roleIds == null ? List.of() : roleIds;
        }
    }

    /**
     * @param builtin 预置角色不可修改或删除，其权限为全部有效权限码，{@code permissions} 为空
     * @param owner Owner 角色不经角色分配授予
     */
    record RoleResponse(
            UUID id,
            String code,
            String name,
            boolean builtin,
            boolean owner,
            long memberCount,
            List<String> permissions) {
        static RoleResponse from(RoleDetail value) {
            return new RoleResponse(
                    value.id(),
                    value.code(),
                    value.name(),
                    value.builtin(),
                    value.owner(),
                    value.memberCount(),
                    value.permissions());
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
