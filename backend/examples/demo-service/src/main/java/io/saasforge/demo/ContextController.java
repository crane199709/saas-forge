package io.saasforge.demo;

import io.saasforge.sdk.security.TenantContextResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ContextController {
    @Operation(summary = "返回经过 SDK 验签的当前身份、租户与成员关系")
    @GetMapping("/context")
    TenantContextResponse context() {
        return TenantContextResponse.current();
    }
}
