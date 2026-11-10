package io.saasforge.iam.access.controller;

import io.saasforge.iam.access.service.AccessService;
import io.saasforge.iam.access.service.AccessService.PlatformAccess;
import io.saasforge.iam.navigation.model.NavigationLayout;
import io.saasforge.iam.subapp.model.LoadableSubApp;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "访问权限")
@RestController
class AccessController {
    private final AccessService access;

    AccessController(AccessService access) {
        this.access = access;
    }

    @Operation(summary = "当前 Membership 的 Permission Code、按权限与套餐裁剪后的菜单树、Shell App 可加载的 Sub-App 与当前环境的入口地址，以及租户的导航布局")
    @GetMapping("/auth/access")
    ApiResponse<AccessResponse> access(@AuthenticationPrincipal Jwt jwt) {
        var value = access.of(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getClaimAsString("membership_id")));
        return ApiResponse.ok(new AccessResponse(value.permissions(), value.menus(), value.subApps(), value.layout()));
    }

    // 只接受 Platform Realm Token（网关按 /iam/platform/** 校验，iam 以 Platform Realm 的解码器验签），Operator 取自 Token 的 sub。
    @Operation(summary = "当前 Operator 的平台角色与有效权限码，以及平台控制台的导航布局，platform-console 据此显示功能")
    @GetMapping("/platform/access")
    ApiResponse<PlatformAccessResponse> platformAccess(@AuthenticationPrincipal Jwt jwt) {
        PlatformAccess value = access.platformOf(UUID.fromString(jwt.getSubject()));
        return ApiResponse.ok(new PlatformAccessResponse(value.roles(), value.permissions(), value.layout()));
    }

    record AccessResponse(
            List<String> permissions, List<MenuNode> menus, List<LoadableSubApp> subApps, NavigationLayout layout) {}

    /** @param roles 平台角色编码，含超级管理员时为 {@code super-admin} */
    record PlatformAccessResponse(List<String> roles, List<String> permissions, NavigationLayout layout) {}
}
