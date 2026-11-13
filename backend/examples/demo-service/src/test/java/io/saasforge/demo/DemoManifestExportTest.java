package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import io.saasforge.sdk.manifest.ManifestCli;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class DemoManifestExportTest {
    @Test
    void exportsTheDemoManifestWithoutStartingTheApplication() throws Exception {
        Path output = Path.of(
                System.getProperty("basedir"),
                "target",
                "permission-manifests",
                "demo-service",
                "resource-manifest.json");
        var configuration = new YamlPropertiesFactoryBean();
        configuration.setResources(new ClassPathResource("application.yml"));
        ManifestCli.main(new String[] {
            "--service=demo-service",
            "--version=" + configuration.getObject().getProperty("forge.manifest.version"),
            "--base-package=io.saasforge.demo",
            "--output=" + output
        });

        ResourceManifest manifest =
                JsonMapper.builder().build().readValue(Files.readString(output), ResourceManifest.class);
        manifest.validate();
        assertThat(manifest.service()).isEqualTo("demo-service");
        assertThat(manifest.version()).isEqualTo("9");
        assertThat(manifest.platform()).isFalse();
        assertThat(manifest.permissions())
                .containsExactly(
                        new Permission("masking:export", "导出脱敏样例", "数据安全示例"),
                        new Permission("masking:read", "查看脱敏样例", "数据安全示例"),
                        new Permission("masking:viewRaw", "查看样例原文", "数据安全示例"),
                        new Permission("order:create", "创建订单", "订单"),
                        new Permission("order:delete", "删除订单", "订单"),
                        new Permission("order:export", "导出订单", "订单"),
                        new Permission("order:import", "导入订单", "订单"),
                        new Permission("order:importRollback", "回滚订单导入", "订单"),
                        new Permission("order:read", "查看订单", "订单"),
                        new Permission("order:update", "修改订单", "订单"));
    }
}
