package io.saasforge.sdk.mq;

import io.saasforge.sdk.core.api.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class DeadEventController {
    private final DeadEventOperations events;

    DeadEventController(DeadEventOperations events) {
        this.events = events;
    }

    @GetMapping("/internal/ops/dead-events")
    ApiResponse<List<DeadEventResponse>> list(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return events.list(status, page, size);
    }

    @GetMapping("/internal/ops/dead-events/{id}")
    ApiResponse<DeadEventResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(events.get(id));
    }

    @PostMapping("/internal/ops/dead-events/{id}/replay")
    ApiResponse<DeadEventResponse> replay(@PathVariable UUID id, @RequestBody ResolutionRequest request) {
        return ApiResponse.ok(events.replay(id, request.reason()));
    }

    @PostMapping("/internal/ops/dead-events/{id}/abandon")
    ApiResponse<DeadEventResponse> abandon(@PathVariable UUID id, @RequestBody ResolutionRequest request) {
        return ApiResponse.ok(events.abandon(id, request.reason()));
    }

    record ResolutionRequest(String reason) {}
}
