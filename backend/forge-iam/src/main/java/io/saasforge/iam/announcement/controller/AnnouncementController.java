package io.saasforge.iam.announcement.controller;

import io.saasforge.iam.announcement.model.AnnouncementView;
import io.saasforge.iam.announcement.service.AnnouncementService;
import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.sdk.core.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AnnouncementController {
    private final AnnouncementService announcements;

    AnnouncementController(AnnouncementService announcements) {
        this.announcements = announcements;
    }

    @GetMapping("/announcements")
    ApiResponse<List<AnnouncementView>> visible(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(announcements.visible(TenantActor.of(jwt)));
    }

    @GetMapping("/announcements/unread-count")
    ApiResponse<UnreadCount> unread(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(new UnreadCount(announcements.unread(TenantActor.of(jwt))));
    }

    @PostMapping("/announcements/{id}/read")
    ApiResponse<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        announcements.read(TenantActor.of(jwt), id);
        return ApiResponse.ok();
    }

    @PostMapping("/announcements/manage")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<AnnouncementView> publish(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PublishRequest request) {
        return ApiResponse.created(announcements.publish(
                TenantActor.of(jwt),
                request.title(),
                request.body(),
                request.subApp(),
                request.route(),
                request.expiresAt()));
    }

    @GetMapping("/announcements/{id}")
    ApiResponse<AnnouncementView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ApiResponse.ok(announcements.get(TenantActor.of(jwt), id));
    }

    @GetMapping("/announcements/manage")
    ApiResponse<List<AnnouncementView>> manage(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(announcements.tenantList(TenantActor.of(jwt)));
    }

    @PostMapping("/announcements/manage/{id}/revoke")
    ApiResponse<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        announcements.revoke(TenantActor.of(jwt), id);
        return ApiResponse.ok();
    }

    record UnreadCount(long count) {}

    record PublishRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String body,
            @Size(max = 64) String subApp,
            @Size(max = 2048) String route,
            Instant expiresAt) {}
}
