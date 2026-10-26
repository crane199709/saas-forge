package io.saasforge.sdk.security;

import io.saasforge.sdk.core.api.ApiResponse;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只为测试安全错误输出的最小业务服务，同时引入 forge-sdk-web。 */
@SpringBootApplication
@EnableMethodSecurity
class TestApplication {

    @RestController
    static class ProbeController {
        @GetMapping("/context")
        ApiResponse<String> context() {
            return ApiResponse.ok(TenantContext.current().tenantId().toString());
        }

        @GetMapping("/admin")
        @PreAuthorize("hasAuthority('SCOPE_admin')")
        ApiResponse<String> admin() {
            return ApiResponse.ok("admin");
        }
    }
}
