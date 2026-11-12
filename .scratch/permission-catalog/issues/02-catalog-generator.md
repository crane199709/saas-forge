# 02: 目录生成与产物入库

**What to build:** 按 [spec](../spec.md) 的“实现决策”，根脚本合并各服务 Manifest，生成确定性的 `permission-catalog.json` 并入库。

**Blocked by:** 01

**Status:** resolved

- [x] 脚本读取各服务 Manifest 产物，合并、去重、排序
- [x] 产物包含 `schemaVersion`、权限码、服务、名称、分组、状态
- [x] 输出 UTF-8、LF、末尾换行；不含时间、路径或运行期数据
- [x] 产物提交到 `docs/contracts/permission-catalog.json`
- [x] 一条命令可重新生成；生成失败时给出缺失服务或非法编码
- [x] 测试覆盖：合并、排序、重复编码、缺失产物

## Comments

- 2026-11-12：工单 02 已领取，在 `codex/permission-catalog-02` 实现；按已批准的生成命令与入库产物接缝执行 TDD。

## Answer

- 根命令：`python3 scripts/permissions/catalog.py`。命令先用当前源码执行 SDK 与三个服务模块的 Maven `install -DskipTests`，再运行 `*ManifestExportTest`，读取四份真实 Resource Manifest；不解析 Java 文本，不启动应用或连接数据库。离线环境运行 `python3 scripts/permissions/catalog.py --offline`。
- Demo 新增 `DemoManifestExportTest`，从 `application.yml` 读取 `forge.manifest.version`，复用 SDK `ManifestCli` 与 collector，产物位于 `backend/examples/demo-service/target/permission-manifests/demo-service/resource-manifest.json`。IAM 两份与 Platform 沿用工单 01 的导出入口。导出前删除旧的四份 target 文件，避免遗漏入口时误用旧文件。
- 目录已入库到 `docs/contracts/permission-catalog.json`：`schemaVersion=1`，扁平 `permissions`，每项为 `service/code/name/group/realm/status`；按权限码排序，UTF-8、LF、末尾换行，无时间或构建路径。首次快照共 28 个 active 权限（IAM 8、IAM Platform 6、Platform 8、Demo 6）。
- 当前 Manifest 中仅完全一致的条目去重；跨服务、Realm、名称或分组冲突均失败。期待服务缺失、产物缺失、非法权限码或无效输入会给出诊断，并在验证全部成功前保留现有目录。
- 默认读取上一版入库目录作为历史；当前缺少的旧码保留名称、分组、归属并标为 `inactive`，同服务同 Realm 再次声明则恢复 `active`。历史编码不可转交其他服务或 Realm，同归属的当前展示信息可正常更新。首次快照仅覆盖当前源码声明，不补查数据库过去未入库的历史。
- 稳定 CLI 参数：`--offline` 仅控制 Maven 离线；`--skip-export` 复用默认四份 target 产物；重复 `--manifest PATH` 合并已有外部 Manifest 并跳过构建；重复 `--expected-service SERVICE` 检查期待服务；`--output PATH` 指定目录位置；`--previous PATH` 指定历史目录（默认仓内目录，首次不存在时为空；显式指定但不存在会失败）。仓内模式始终要求上述四服务。
- 外部 CLI 示例：`python3 scripts/permissions/catalog.py --manifest /tmp/orders/resource-manifest.json --expected-service orders --previous docs/contracts/permission-catalog.json --output /tmp/orders-catalog.json`。
- TDD 按已批准的“生成命令 + 产物”接缝逐个 red → green。`python3 -m unittest discover -s scripts/permissions -p 'test_*.py' -v`：9 项通过，覆盖合并排序、UTF-8/LF、重复字节一致、完全一致去重、归属和展示元数据冲突、缺失文件/期待服务、非法码、历史停用/恢复、历史归属冲突、无效历史和 SDK 空分组兼容。测试全部通过外部 CLI 执行。
- 真实根命令 `python3 scripts/permissions/catalog.py --offline` 连续两次成功，5 个 Java 导出测试通过，两次目录 `cmp` 字节一致。根命令的源码构建同时通过 Spotless 检查。已合入集成分支 `ca5b29b`。
- 交付前唯一一次 Demo 模块全量测试完成：`./mvnw -o -pl examples/demo-service test`，共 106 项通过，0 失败、0 错误、0 跳过；日志 `/tmp/permission-catalog-02-demo-full-tests.log`。IAM 与 Platform 全量沿用工单 01 已通过的 379 项，不重复执行。
- 本工单不实现动作词策略、`--check`、CI 或消费文档；工单 03 可直接在上述 CLI 与字段结构上扩展。
