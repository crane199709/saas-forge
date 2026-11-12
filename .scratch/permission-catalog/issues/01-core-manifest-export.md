# 01: 核心服务 Manifest 导出

**What to build:** 按 [spec](../spec.md) 的“实现决策”，让核心 IAM/Platform 也能输出与业务服务同格式的 Manifest JSON。

**Blocked by:** —

**Status:** resolved

- [x] IAM 与 Platform 的权限清单可通过构建/测试命令导出为 JSON
- [x] 导出内容包含服务名、版本、权限码、名称、分组与 Realm
- [x] 输出稳定：字段顺序与集合顺序确定，可直接参与字节比较
- [x] 不依赖运行中的数据库
- [x] 测试覆盖：导出成功、内容包含核心权限、重复导出字节一致

## Comments

- 2026-11-12：工单 01 已领取，在独占分支 `codex/permission-catalog-01` 实现。测试接缝为 Manifest 导出结果与构建/测试命令。

## Answer

- IAM 新增 `io.saasforge.iam.permission.service.IamManifestCli`，直接复用 `IamPermissions.manifest()` 和 `PlatformPermissions.manifest()`，不启动 Spring、不连接数据库。两份产物显式包含 Realm，权限按编码排序，字段顺序固定，UTF-8、LF、末尾换行。
- `forge-platform` 复用 SDK 的 `ManifestCli`，版本由主 `application.yml` 的 `forge.manifest.version` 读取；保留原有 Resource Manifest 格式。
- 从 `backend/` 执行以下命令，先安装当前 SDK，再一次导出三份核心 Manifest：

  ```sh
  ./mvnw -o -pl forge-iam,forge-platform -am install -DskipTests
  ./mvnw -o -pl forge-iam,forge-platform test '-Dtest=*ManifestExportTest'
  ```

- 输出路径（相对仓库根目录）：
  - `backend/forge-iam/target/permission-manifests/forge-iam/resource-manifest.json`
  - `backend/forge-iam/target/permission-manifests/forge-iam-platform/resource-manifest.json`
  - `backend/forge-platform/target/permission-manifests/forge-platform/resource-manifest.json`
- IAM CLI 也可在模块编译产物和运行依赖组成的 classpath 上直接执行：`java -cp <classpath> io.saasforge.iam.permission.service.IamManifestCli --output-dir=<directory>`。
- 测试接缝：Manifest 导出结果与构建/测试命令。两轮 red → green 分别记录了入口缺失、权限列表未排序的预期失败；`IamManifestExportTest` 与 `PlatformManifestExportTest` 共 4 个测试已通过，涵盖全部核心权限、名称、分组、版本、Realm、重复导出字节一致。Spotless 已通过。
- 交付前唯一一次受影响模块全量测试已启动：`./mvnw -o -pl forge-iam,forge-platform test`，日志 `/tmp/permission-catalog-01-full-tests.log`。最终结论随全量测试完成补充。
- 工单 02 可按 `*ManifestExportTest` 模式统一选择导出入口，收集上述三份核心产物；本工单没有修改 demo-service 或实现合并目录。
