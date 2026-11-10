package io.saasforge.iam.navigation.controller;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.navigation.model.NavigationLayout;
import io.saasforge.iam.navigation.service.NavigationLayoutService;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "导航布局")
@RestController
class NavigationLayoutController {
    private final NavigationLayoutService layouts;

    NavigationLayoutController(NavigationLayoutService layouts) {
        this.layouts = layouts;
    }

    @Operation(summary = "当前租户的导航布局与管理页目录（按套餐裁剪、不按操作者本人权限裁剪的清单菜单，不含页面操作）")
    @GetMapping("/navigation-layout")
    ApiResponse<TenantLayoutResponse> tenant(@AuthenticationPrincipal Jwt jwt) {
        TenantActor actor = TenantActor.of(jwt);
        return ApiResponse.ok(new TenantLayoutResponse(layouts.tenantLayout(actor), layouts.tenantCatalog(actor)));
    }

    @Operation(summary = "保存当前租户的导航布局，只校验结构，不校验引用是否存在")
    @PutMapping("/navigation-layout")
    ApiResponse<NavigationLayout> replaceTenant(
            @AuthenticationPrincipal Jwt jwt, @RequestBody NavigationLayout layout) {
        return ApiResponse.ok(layouts.replaceTenantLayout(TenantActor.of(jwt), layout));
    }

    @Operation(summary = "恢复当前租户的默认导航")
    @DeleteMapping("/navigation-layout")
    ApiResponse<Void> resetTenant(@AuthenticationPrincipal Jwt jwt) {
        layouts.resetTenantLayout(TenantActor.of(jwt));
        return ApiResponse.ok();
    }

    // 只接受 Platform Realm Token（网关按 /iam/platform/** 校验，iam 以 Platform Realm 的解码器验签），Operator 取自 Token 的 sub。
    @Operation(summary = "平台控制台的导航布局")
    @GetMapping("/platform/navigation-layout")
    ApiResponse<PlatformLayoutResponse> platform(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(new PlatformLayoutResponse(layouts.platformLayout(operator(jwt))));
    }

    @Operation(summary = "保存平台控制台的导航布局，只校验结构，不校验引用是否存在")
    @PutMapping("/platform/navigation-layout")
    ApiResponse<NavigationLayout> replacePlatform(
            @AuthenticationPrincipal Jwt jwt, @RequestBody NavigationLayout layout) {
        return ApiResponse.ok(layouts.replacePlatformLayout(operator(jwt), layout));
    }

    @Operation(summary = "恢复平台控制台的默认导航")
    @DeleteMapping("/platform/navigation-layout")
    ApiResponse<Void> resetPlatform(@AuthenticationPrincipal Jwt jwt) {
        layouts.resetPlatformLayout(operator(jwt));
        return ApiResponse.ok();
    }

    private static UUID operator(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * @param layout 布局，没有时为 null
     * @param menus 管理页目录
     */
    record TenantLayoutResponse(NavigationLayout layout, List<MenuNode> menus) {}

    /** @param layout 布局，没有时为 null */
    record PlatformLayoutResponse(NavigationLayout layout) {}
}
