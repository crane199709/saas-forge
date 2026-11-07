package io.saasforge.iam.notification.controller;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.notification.model.NotificationResponse;
import io.saasforge.iam.notification.service.NotificationService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.web.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class NotificationController {
    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Operation(summary = "分页查看当前Membership收件箱")
    @GetMapping("/notifications")
    public ApiResponse<List<NotificationResponse>> list(@AuthenticationPrincipal Jwt jwt, PageQuery page) {
        TenantActor actor = TenantActor.of(jwt);
        return ApiResponse.page(
                notifications.page(actor, page.offset(), page.size()), page.toPageMeta(notifications.count(actor)));
    }

    @Operation(summary = "当前Membership通知未读数")
    @GetMapping("/notifications/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(new UnreadCountResponse(notifications.unreadCount(TenantActor.of(jwt))));
    }

    @Operation(summary = "标记当前Membership一条通知已读")
    @PostMapping("/notifications/{id}/read")
    public ApiResponse<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        notifications.markRead(TenantActor.of(jwt), id);
        return ApiResponse.ok();
    }

    @Operation(summary = "标记当前Membership全部通知已读")
    @PostMapping("/notifications/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal Jwt jwt) {
        notifications.markRead(TenantActor.of(jwt), null);
        return ApiResponse.ok();
    }

    record UnreadCountResponse(long count) {}
}
