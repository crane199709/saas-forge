package io.saasforge.sdk.ops;

import io.saasforge.sdk.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class OpsController {
    @GetMapping("/internal/ops")
    ApiResponse<Boolean> available() {
        return ApiResponse.ok(true);
    }
}
