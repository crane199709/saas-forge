package io.saasforge.iam.permission.service;

import io.saasforge.sdk.core.ResourceManifest;
import io.saasforge.sdk.core.ResourceManifest.Permission;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 不启动应用，导出 IAM 运行时登记的两份 Resource Manifest。 */
public final class IamManifestCli {
    private IamManifestCli() {}

    /**
     * 将清单写入输出目录下的 {@code <service>/resource-manifest.json}。
     *
     * @param args 唯一参数为 {@code --output-dir=<directory>}
     * @throws Exception 参数无效或清单无法写入
     */
    public static void main(String[] args) throws Exception {
        String prefix = "--output-dir=";
        if (args.length != 1
                || !args[0].startsWith(prefix)
                || args[0].substring(prefix.length()).isBlank()) {
            throw new IllegalArgumentException("--output-dir=<directory> is required");
        }
        Path output = Path.of(args[0].substring(prefix.length()));
        JsonMapper json = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .build();
        for (ResourceManifest manifest : List.of(IamPermissions.manifest(), PlatformPermissions.manifest())) {
            manifest.validate();
            ObjectNode document = json.valueToTree(manifest);
            document.set(
                    "permissions",
                    json.valueToTree(manifest.permissions().stream()
                            .sorted(Comparator.comparing(Permission::code))
                            .toList()));
            // 构建期产物显式标注 Realm；ResourceManifest 仍按原契约把 tenant 规范化为 null。
            document.put(
                    "realm", manifest.platform() ? ResourceManifest.PLATFORM_REALM : ResourceManifest.TENANT_REALM);
            Path file = output.resolve(manifest.service()).resolve("resource-manifest.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(document) + "\n");
        }
    }
}
