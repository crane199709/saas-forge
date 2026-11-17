# 工单 05：历史未发布 IO 基线增量升级验收

日期：2026-11-17。工单：[05](issues/05-io-upgrade.md)。本票验收通过；不代表全部可选能力、试点或发布就绪通过。

## 历史基线与候选身份

实际发布的 `v0.1.0` 无 IO 模块，对应已发布 Maven POM/JAR 不存在。本票按用户补充确认采用可核对历史源码构建的**未发布基线**，没有制造或使用所谓“已发布 IO 0.1.0”。默认工程、Sub-App、storage 的旧版输入规则保持为真实发布制品。

- 实现起点：集成 `0ad3e158`，分支 `codex/v020-upgrade-05`。
- 历史源码：`d9f2b032ebae06000e476ba3fe22f4ff90e88a99`，即首次快照提交 `c18cc5e3` 的父提交；已有确认式导入、导出、IO task 和 storage purpose，尚无 import backup。Git tree：`11453323773334eee62c77f0e0c2ee98e997c58b`。
- 历史构建使用 app 创建的独立 managed 工作树 `/Users/liuhuan/.codex/worktrees/v020-io-history/saas-forge`。构建前后 `git status --porcelain` 为空，源码未修改；该工作树交协调代理最终归档。
- 历史源码原版本 `0.2.0-SNAPSHOT`；仅通过 `-Drevision=0.2.0-io-history-d9f2b032-SNAPSHOT` 覆盖构建坐标，执行 `-pl examples/demo-service -am install -DskipTests`。59 个实际 POM/JAR 的完整摘要在 `05-green/baseline.json`，均只安装到本轮隔离 Maven 缓存，未发布到目标仓库。
- 独立样例来自历史 Demo 的实际业务源码及 V1—V14；业务坐标 `io.saasforge:retained-io-service:7.5.0-SNAPSHOT`，业务版本与 SDK 版本分开。Manifest 配置版本 `8`、业务路由、模型及自有配置保持原样。只将样例 POM 改为外部消费形式、调整平台依赖版本；50 个原 `src/main` 文件在升级和格式化后均保持字节一致。
- 候选目录 `/private/tmp/v020-upgrade/candidate-54115992`，源码提交 `54115992e00691be57b912e46593709c77c344a1`，版本 `0.2.0-SNAPSHOT`；候选清单 SHA-256 `999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`。本票只新增验收/说明，不改候选制品源码，不能将脚本提交当作包来源。

| 实际输入 | SHA-256 |
| --- | --- |
| 历史 IO JAR | `5e4d51e8a2073a86772e6ed9278471460e073ea853eb723a4464d816c46e8b6a` |
| 历史 IO schema.sql | `93a0922b4970998cc3f808437419999e52bbdb9501454dfa6a58eefd144134fa` |
| 候选 IO JAR | `97a7b7ea17c3f951912935527b624a8d5e6cbe5765401f097a54b734e506d05d` |
| 候选 IO schema.sql | `4053959a94eed99a962e087ac9b88132f6dab95838b4902e19ad4443aeb0fb4b` |
| 候选 storage JAR | `bfaa86493fa693245ea64116b02bcf42da5d5eeeb9c61b71a58ad22bfee884e0` |
| 旧样例可执行 JAR | `9871898e35bccb0912e8f642720239bb568df31d7d8191eb969edd3d93e52b84` |
| 候选样例可执行 JAR | `a8e3c99713bc4775aec1cbbe989d7f9c5aed6e21f730eee4e6c704789c3a936e` |

本机 `~/.m2/repository/io/saasforge` 全部排除，只复用第三方缓存。旧、新可执行 JAR 内嵌 IO 包的 SHA-256 分别与独立历史构建和实际候选一致，避免同名 SNAPSHOT 或本机 install 缓存混用。

## 交付与增量迁移

入口 `deploy/upgrade-smoke/io.sh`，固定样例实现 `io_upgrade.py`，HTTP 验收夹具 `fixtures/io/IoUpgradeAcceptanceTest.java`。复用 02 的运行/摘要与仓库外消费者组织方式；IO 本身没有已发布 0.1.0 输入，不调用发布下载器制造不存在的包。

升级说明：[IO 0.2.0](../../docs/upgrades/io-0.2.0.md)。业务指南、默认服务升级清单及 CHANGELOG 已接入专项说明。未增加编码规则、默认 starter 依赖、产品升级接口或业务自动覆盖工具。

以下 SQL 均按原字节从实际目标 SDK JAR 提取，保留中文注释与 RLS；没有复制完整 `schema.sql` 到既有库，没有重复 IO task、死信或 purpose 的建表/加列步骤。

| 新增迁移 | SHA-256 |
| --- | --- |
| V15__import_backup.sql | `aedffb7fc330d94367e3a508ade7dce399c32749ae2e36b7c16042f25b12eb4e` |
| V16__import_rollback_preview.sql | `bc8d97aa918fdafcf0abb956542c76d9f45254e10ec351e1717464bf5e704aad` |
| V17__import_rollback_execution.sql | `5da35379a12fe86651ae7dfa6659f4165a414b8ed182c4733ac4ff73bf8d9816` |
| V18__storage_retention.sql | `f014d92fbbea8eed8eb1d246e6707f907da48719d50a873b7453d9e4e00c1f80` |

## 红绿与实际结果

1. **IO 结构红灯**：`/private/tmp/v020-upgrade/05-red/verify-upgraded.log`。真实历史 JAR 先经业务 HTTP 建立成功导入、校验失败、成功导出、待确认导入四任务与两个订单；停止旧进程，候选 JAR 使用同一隔离数据库后 `GET /io/tasks` 返回 `500 system.error`。`runtime-upgraded.log` 明确 `import_model` 缺列、`forge_import_backup` 缺表。
2. **storage 依赖红灯**：`05-storage-red/verify-upgraded.log`。补 IO 三条迁移后，四任务的状态、行数、时间、NULL 模型及快照/回滚默认值均通过；旧导出下载返回 500，日志明确 `retention_expires_at` 缺列。历史已有 purpose，故只追加目标 storage 的 retention 迁移，独立于 04 的发布旧版 storage 场景。
3. **完整绿灯**：`05-green/verify-upgraded.log`，独立样例完整 `verify`：**1 test，0 failures，0 errors，0 skipped**；同时通过 Checkstyle、依赖门禁、Spotless、编译与打包。新增入库 Java 夹具与这次实际格式化、编译和执行的文件完全一致。
4. 旧任务读取与列表保留原 ID、类型、提交人、状态、行数、错误原因、心跳/创建/完成时间和结果可用性。历史 `importModel` 保持 NULL，`snapshotCount=0`、截止为空、`snapshotAvailable=false`、`rollbackStatus=NONE`、回滚计数全零；不伪造历史模型、快照或回滚结果。
5. 旧导出及错误报告经实际预签名 HTTP 再次下载，内容字节一致。`before-export.xlsx` 与 `retained-export.xlsx` SHA-256 同为 `ce5d41ca9b8d61f27a3a15f1e58078a561f1b200e2f498b594e579fdaad245e1`；错误报告前后同为 `8f7202dce935d5cee2708a187895f0b9e5a25ce5521a5d11ce2f4bcc09f45472`。
6. 历史待确认任务在候选上经**原业务确认入口**重新校验、写入成功，仍保留 NULL 模型；其他成员确认 403、跨租户确认 404、重复确认 409。候选新建任务记录实际模型，新导入/导出成功；订单从 2 个增至 4 个，任务从 4 个增至 6 个。导出工作簿包含所有旧/新业务行，失败报告包含实际错误列。
7. 本人任务可见，同租户其他成员/其他租户访问各旧任务为 404，跨租户下载和订单读取 404，匿名任务列表 401。
8. Flyway 公共接口记录 14 → 18；前 14 条的版本、脚本、checksum、安装时间与账号完全相同。三阶段实际 JDBC 目标均为 `jdbc:postgresql://localhost:64746/forge_1?loggerLevel=OFF`、schema `demo`，运行账号 `demo_app`、迁移账号 `demo_migrator`。Redis/S3 也在三阶段复用同一本轮实例。候选再次启动后迁移历史完全不变、待确认任务仍为已成功、业务订单仍为 4 个。

没有执行 clean/repair，没有改写 V1—V14、清库或切换到新库规避迁移。所有容器属于本轮验收并由 Testcontainers 回收，不操作共享/生产数据。

## 复现、证据与边界

```sh
bash scripts/release/candidate.sh use /private/tmp/v020-upgrade/candidate-54115992 \
  bash deploy/upgrade-smoke/io.sh /private/tmp/v020-upgrade/05-reproduce \
  /Users/liuhuan/.codex/worktrees/v020-io-history/saas-forge
```

证据目录必须尚不存在。最后一项可省略，入口会从本仓库 `git archive` 导出同一固定提交；CI 须能解析该提交。本轮完整红绿均使用上面的 managed 历史工作树参数。16GiB 机器的完整 Maven/容器命令均经 `python3 /private/tmp/v020-upgrade/run-heavy.py <命令...>` 串行执行。

最终证据目录 `/private/tmp/v020-upgrade/05-green/`：`baseline.json`、`http-evidence.json`、完整构建/解析/验证日志、`runtime-old.log`、`runtime-upgraded.log`、`runtime-restarted.log`、五个实际下载 Excel、旧/新工程和 JAR。大文件不入库。两次红灯分别位于 `05-red/` 与 `05-storage-red/`，驱动日志为相邻同名 `.log`。

轻量验证：Python 3.9 AST、Shell 语法、`git diff --check` 通过。实测 JDK 17.0.12、Maven Wrapper 3.9.14、PostgreSQL 17.11、Redis 7、S3 `pgsty/silo:RELEASE.2026-10-18T00-00-00Z`、macOS/Docker Desktop。

验收使用既有 JWKS 签名 JWT、Redis Membership Grants、真实 HTTP/数据库/S3 与 Flyway 公共接口。IAM 字典/租户目录沿用既有 HTTP 测试接缝；MQ、Nacos、远程 Manifest、敏感字段脱敏及租户生命周期写保护关闭。本票不替代完整 IAM/网关/Shell、Broker、前端任务列表、覆盖导入回滚执行或到期清理专项。该历史基线没有已有快照/已完成回滚结果，故只验证其 NULL/零值升级语义，不宣称验收不存在的历史数据。基线已使用三参导入与单独确认，无须再调整业务 API，不为测试增加覆盖导入功能。

未向目标 Maven/npm 仓库上传、未切换共享环境。候选制品或历史基线变化后须重新判断并复验受影响部分，不能沿用不匹配的证据。
