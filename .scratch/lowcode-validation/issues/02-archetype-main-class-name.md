# 02: archetype 主类固定为 DemoApplication

**Status:** needs-triage

**现象：** `archetype-resources/src/main/java/DemoApplication.java` 不随 `serviceName` / `artifactId` 变化，生成 `lowcode-service` 后主类仍叫 `DemoApplication`。

**业务工程绕行：** 手动改名为 `LowcodeApplication`（lowcode 仓库提交 `6dcb220`），`ArchitectureTest`、`OrderApiTest` 通过。

**期望：** 主类名由生成参数派生（如新增 `applicationClassName` 属性，默认由 `artifactId` 去掉 `-service` 后转 PascalCase 加 `Application`），`archetype-it` 断言生成的类名。
