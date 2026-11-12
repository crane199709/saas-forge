package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.permission.service.IamManifestCli;
import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class IamManifestExportTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path OUTPUT = Path.of(System.getProperty("basedir"), "target", "permission-manifests");

    @Test
    void exportsBothCoreManifestsWithoutStartingTheApplication() throws Exception {
        IamManifestCli.main(new String[] {"--output-dir=" + OUTPUT});

        String tenantJson = Files.readString(OUTPUT.resolve("forge-iam/resource-manifest.json"));
        ResourceManifest tenant = JSON.readValue(tenantJson, ResourceManifest.class);
        tenant.validate();
        assertThat(tenant.service()).isEqualTo("forge-iam");
        assertThat(tenant.version()).isEqualTo("7");
        assertThat(JSON.readTree(tenantJson).path("realm").asText()).isEqualTo("tenant");
        assertThat(tenant.permissions()).contains(new Permission("iam:member:manage", "管理成员", "租户管理"));
        assertThat(tenant.permissions())
                .extracting(Permission::code)
                .containsExactlyInAnyOrder(
                        "iam:member:manage",
                        "iam:org:manage",
                        "iam:role:manage",
                        "iam:audit:read",
                        "iam:reference-data:manage",
                        "iam:security:manage",
                        "announcement:manage",
                        "iam:menu:manage");

        ResourceManifest platform = JSON.readValue(
                Files.readString(OUTPUT.resolve("forge-iam-platform/resource-manifest.json")), ResourceManifest.class);
        platform.validate();
        assertThat(platform.service()).isEqualTo("forge-iam-platform");
        assertThat(platform.version()).isEqualTo("5");
        assertThat(platform.realm()).isEqualTo("platform");
        assertThat(platform.permissions()).contains(new Permission("platform:operator:manage", "管理运营账号", "平台管理"));
        assertThat(platform.permissions())
                .extracting(Permission::code)
                .containsExactlyInAnyOrder(
                        "platform:operator:manage",
                        "platform:role:manage",
                        "platform:audit:read",
                        "platform:identity-mfa:reset",
                        "platform:announcement:manage",
                        "platform:menu:manage");
    }

    @Test
    void repeatedExportsAreSortedAndByteIdentical(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        IamManifestCli.main(new String[] {"--output-dir=" + first});
        IamManifestCli.main(new String[] {"--output-dir=" + second});

        for (String service : List.of("forge-iam", "forge-iam-platform")) {
            Path file = first.resolve(service).resolve("resource-manifest.json");
            ResourceManifest manifest = JSON.readValue(Files.readString(file), ResourceManifest.class);
            assertThat(manifest.permissions()).extracting(Permission::code).isSorted();
            assertThat(Files.readAllBytes(file))
                    .isEqualTo(Files.readAllBytes(second.resolve(service).resolve("resource-manifest.json")));
            assertThat(Files.readString(file)).doesNotContain("\r").endsWith("\n");
        }
    }
}
