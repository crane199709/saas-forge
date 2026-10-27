package io.saasforge.sdk.manifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 在 CI 中生成并可选上报 Resource Manifest，不启动应用。以服务的编译产物与依赖为 classpath 运行：
 *
 * <pre>{@code
 * java -cp <classpath> io.saasforge.sdk.manifest.ManifestCli \
 *     --service=demo-service --version=1.0.0 --base-package=io.saasforge.demo \
 *     --output=target/resource-manifest.json [--iam-uri=http://iam:8101]
 * }</pre>
 *
 * <p>给出 {@code --iam-uri} 时同时上报，令牌从环境变量 {@code FORGE_MANIFEST_TOKEN} 读取，不出现在命令行与进程列表中。
 */
public final class ManifestCli {
    private ManifestCli() {}

    /**
     * 生成清单写入 {@code --output}，给出 {@code --iam-uri} 时上报。
     *
     * @param args {@code --name=value} 形式的参数
     * @throws Exception 参数缺失、清单无效、写文件或上报失败；以非零退出码结束 CI 步骤
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            int split = arg.indexOf('=');
            if (!arg.startsWith("--") || split < 0) throw new IllegalArgumentException("Unexpected argument: " + arg);
            options.put(arg.substring(2, split), arg.substring(split + 1));
        }
        var manifest = ResourceManifestCollector.collect(
                required(options, "service"),
                required(options, "version"),
                required(options, "base-package"),
                Thread.currentThread().getContextClassLoader());
        Path output = Path.of(required(options, "output"));
        if (output.toAbsolutePath().getParent() != null)
            Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(
                output,
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        String iamUri = options.get("iam-uri");
        if (iamUri != null) {
            String token = System.getenv("FORGE_MANIFEST_TOKEN");
            if (token == null || token.isBlank())
                throw new IllegalArgumentException("FORGE_MANIFEST_TOKEN must be set");
            new ManifestReporter(iamUri, token).report(manifest);
        }
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + name + " is required");
        return value;
    }
}
