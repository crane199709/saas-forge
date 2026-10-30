package io.saasforge.iam.access.controller;

import io.saasforge.iam.access.service.AccessService;
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

    @Operation(summary = "当前 Membership 的 Permission Code、按权限与套餐裁剪后的菜单树，以及 Shell App 可加载的 Sub-App 与当前环境的入口地址")
    @GetMapping("/auth/access")
    ApiResponse<AccessResponse> access(@AuthenticationPrincipal Jwt jwt) {
        var value = access.of(
                UUID.fromString(jwt.getClaimAsString("tenant_id")),
                UUID.fromString(jwt.getClaimAsString("membership_id")));
        return ApiResponse.ok(new AccessResponse(value.permissions(), value.menus(), value.subApps()));
    }

    record AccessResponse(List<String> permissions, List<MenuNode> menus, List<LoadableSubApp> subApps) {}
}
