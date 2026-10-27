package io.saasforge.platform.operator.controller;

import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.OperatorContext;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OperatorController {
    @Operation(summary = "返回经过 SDK 验签的当前 Operator")
    @GetMapping("/operators/current")
    ApiResponse<CurrentOperatorResponse> current() {
        return ApiResponse.ok(
                new CurrentOperatorResponse(OperatorContext.current().operatorId()));
    }

    record CurrentOperatorResponse(UUID operatorId) {}
}
