package io.saasforge.sdk.security;

import io.saasforge.sdk.core.api.ApiResponse;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

        @GetMapping("/log-context")
        ApiResponse<Map<String, String>> logContext() {
            LoggerFactory.getLogger(ProbeController.class).info("probe log line");
            return ApiResponse.ok(Map.of(
                    "tenant_id", String.valueOf(MDC.get("tenant_id")),
                    "membership_id", String.valueOf(MDC.get("membership_id"))));
        }

        @GetMapping("/orders")
        @RequiresPermission("order:read")
        ApiResponse<String> orders() {
            return ApiResponse.ok("orders");
        }

        @GetMapping("/admin")
        @PreAuthorize("hasAuthority('SCOPE_admin')")
        ApiResponse<String> admin() {
            return ApiResponse.ok("admin");
        }
    }

    // 类上的声明对全部方法生效，方法上的声明优先。
    @RestController
    @RequiresPermission("report:read")
    static class ReportController {
        @GetMapping("/reports")
        ApiResponse<String> reports() {
            return ApiResponse.ok("reports");
        }

        @GetMapping("/reports/export")
        @RequiresPermission("report:export")
        ApiResponse<String> export() {
            return ApiResponse.ok("export");
        }
    }
}
