package io.saasforge.iam.access.controller;

import io.saasforge.iam.access.service.AccessService;
import io.saasforge.sdk.core.ResourceManifest.MenuNode;
import io.saasforge.sdk.core.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccessController {
    private final AccessService access;

    AccessController(AccessService access) {
        this.access = access;
    }

    @Operation(summary = "当前 Membership 的 Permission Code 与按权限裁剪后的菜单树")
    @GetMapping("/auth/access")
    ApiResponse<AccessResponse> access(@AuthenticationPrincipal Jwt jwt) {
        var value = access.of(UUID.fromString(jwt.getClaimAsString("membership_id")));
        return ApiResponse.ok(new AccessResponse(value.permissions(), value.menus()));
    }

    record AccessResponse(List<String> permissions, List<MenuNode> menus) {}
}
