package io.saasforge.iam.announcement.controller;

import io.saasforge.iam.announcement.model.PlatformAnnouncementView;
import io.saasforge.iam.announcement.service.AnnouncementService;
import io.saasforge.sdk.core.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
class PlatformAnnouncementController {
    private final AnnouncementService announcements;

    PlatformAnnouncementController(AnnouncementService announcements) {
        this.announcements = announcements;
    }

    @GetMapping("/platform/announcements")
    ApiResponse<List<PlatformAnnouncementView>> list(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(announcements.platformList(UUID.fromString(jwt.getSubject())));
    }

    @PostMapping("/platform/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<PlatformAnnouncementView> publish(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PublishRequest request) {
        return ApiResponse.created(announcements.publishPlatform(
                UUID.fromString(jwt.getSubject()),
                request.title(),
                request.body(),
                request.subApp(),
                request.route(),
                request.expiresAt(),
                request.allTenants(),
                request.tenantIds()));
    }

    @PostMapping("/platform/announcements/{id}/revoke")
    ApiResponse<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        announcements.revokePlatform(UUID.fromString(jwt.getSubject()), id);
        return ApiResponse.ok();
    }

    record PublishRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20000) String body,
            @Size(max = 64) String subApp,
            @Size(max = 2048) String route,
            Instant expiresAt,
            boolean allTenants,
            List<@NotNull UUID> tenantIds) {
        PublishRequest {
            tenantIds = tenantIds == null ? List.of() : List.copyOf(tenantIds);
        }
    }
}
