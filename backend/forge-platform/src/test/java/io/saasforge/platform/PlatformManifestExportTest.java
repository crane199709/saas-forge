package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.manifest.ManifestCli;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class PlatformManifestExportTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path OUTPUT = Path.of(
            System.getProperty("basedir"),
            "target",
            "permission-manifests",
            "forge-platform",
            "resource-manifest.json");

    @Test
    void exportsThePlatformManifestWithoutStartingTheApplication() throws Exception {
        export(OUTPUT);

        ResourceManifest manifest = JSON.readValue(Files.readString(OUTPUT), ResourceManifest.class);
        manifest.validate();
        assertThat(manifest.service()).isEqualTo("forge-platform");
        assertThat(manifest.version()).isEqualTo("3");
        assertThat(manifest.realm()).isEqualTo("platform");
        assertThat(manifest.permissions())
                .containsExactly(
                        new Permission("platform:onboarding:read", "查看服务接入诊断", "平台运维"),
                        new Permission("platform:ops:manage", "处置运维事件与任务", "平台运维"),
                        new Permission("platform:ops:read", "查看运维数据", "平台运维"),
                        new Permission("platform:plan:manage", "管理套餐", "套餐与应用"),
                        new Permission("platform:service-credential:manage", "管理服务凭证", "平台运维"),
                        new Permission("platform:sub-app:manage", "管理应用注册表", "套餐与应用"),
                        new Permission("platform:tenant:manage", "管理租户", "租户"),
                        new Permission("platform:tenant:read", "查看租户", "租户"));
    }

    @Test
    void repeatedExportsAreSortedAndByteIdentical(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("first.json");
        Path second = directory.resolve("second.json");
        export(first);
        export(second);

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
        ResourceManifest manifest = JSON.readValue(Files.readString(first), ResourceManifest.class);
        assertThat(manifest.permissions()).extracting(Permission::code).isSorted();
    }

    private static void export(Path output) throws Exception {
        var configuration = new YamlPropertiesFactoryBean();
        configuration.setResources(new ClassPathResource("application.yml"));
        ManifestCli.main(new String[] {
            "--service=forge-platform",
            "--version=" + configuration.getObject().getProperty("forge.manifest.version"),
            "--base-package=io.saasforge.platform",
            "--output=" + output
        });
    }
}
