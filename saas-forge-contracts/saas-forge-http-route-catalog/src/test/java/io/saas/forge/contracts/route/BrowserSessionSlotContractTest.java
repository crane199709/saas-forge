package io.saas.forge.contracts.route;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserSessionSlotContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..").toAbsolutePath().normalize();

    @Test
    void publishesTwoBrowserSessionSlotsWithoutChangingTheHistoricalBaseline() throws IOException {
        String current = Files.readString(REPOSITORY_ROOT.resolve("saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml"));
        String baseline = Files.readString(REPOSITORY_ROOT.resolve(
                "saas-forge-contracts/compatibility-baselines/v1/v0.2.0/openapi/v1.yaml"));

        assertTrue(current.contains("PlatformRefreshCookieAuth:"));
        assertTrue(current.contains("name: __Host-sf_platform_refresh"));
        assertTrue(current.contains("TenantRefreshCookieAuth:"));
        assertTrue(current.contains("name: __Host-sf_tenant_refresh"));
        assertTrue(current.contains("required: [sessionSlot]"));
        assertFalse(current.contains("name: __Host-sf_refresh"));

        assertTrue(baseline.contains("name: __Host-sf_refresh"));
        assertFalse(baseline.contains("name: __Host-sf_platform_refresh"));
        assertFalse(baseline.contains("name: __Host-sf_tenant_refresh"));
    }

    @Test
    void backendRepositoryCallersUseTheAtomicSessionSlotProtocol() throws IOException {
        // 前端调用方已迁出；本仓只扫描自有调用方，缺失目录仍应使检查失败。
        for (String directory : List.of("scripts", "test-support")) {
            try (var paths = Files.walk(REPOSITORY_ROOT.resolve(directory))) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    if (path.toString().contains("node_modules") || path.toString().contains("target")) {
                        continue;
                    }
                    String fileName = path.getFileName().toString();
                    if (List.of(".sh", ".ts", ".tsx", ".js", ".mjs", ".json", ".yaml", ".yml", ".md")
                            .stream().noneMatch(fileName::endsWith)) {
                        continue;
                    }
                    String source = Files.readString(path);
                    assertFalse(source.contains("__Host-sf_refresh"), path.toString());
                    if (source.contains("/api/v1/auth/refresh")) {
                        assertTrue(source.contains("sessionSlot"), path.toString());
                    }
                }
            }
        }
    }
}
