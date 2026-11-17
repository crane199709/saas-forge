# IO 历史工程升级至 0.2.0

IO 不存在于实际发布的 `0.1.0` 中。本页使用可核对历史 IO 源码建立**未发布基线**，证明任务和数据的增量迁移；它不构成“已发布 IO 0.1.0”的兼容承诺。默认 Business Service 与 storage 的旧版输入仍使用真实发布制品。

## 选择实际基线

先记录业务工程的源码提交、SDK 版本、已执行 Flyway 脚本与 checksum，并与目标 SDK JAR 中的升级 SQL 对照。不能仅按版本号判断哪些结构已经存在，也不能复制新版 `schema.sql` 重建已存在的 IO 表。

固定验收使用提交 `d9f2b032ebae06000e476ba3fe22f4ff90e88a99`，位于首次快照功能 `c18cc5e3` 之前。该历史源码已有 `AWAITING_CONFIRM`、确认式导入、导出及 storage purpose，尚无快照/回滚列。仅为避免历史与候选同名 SNAPSHOT 混用，以 `-Drevision=0.2.0-io-history-d9f2b032-SNAPSHOT` 构建历史依赖闭包，不修改历史源码；独立样例业务版本为 `7.5.0-SNAPSHOT`。

历史 JAR/POM 仅安装到本轮隔离 Maven 缓存，不向目标仓库发布。缓存只复制第三方依赖，排除本机全部 `io.saasforge`。历史与候选使用不同坐标，来源、构建参数、JAR/schema 和工程文件 SHA-256 均记录在 `baseline.json`。候选包仍由 `candidate.json` 的提交和摘要标识，验收脚本提交不是制品源码提交。

## 只补实际缺少的迁移

保留全部已执行迁移的原字节。下面顺序适用于尚未引入快照的历史结构；若某一步已执行，保留原迁移并从实际缺少的下一步继续，不重复加列或建表。迁移号采用业务工程下一个可用版本，表中 V15—V18 仅属于固定样例。

| 样例迁移 | 从目标实际制品复制的内容 | 保留语义 |
| --- | --- | --- |
| V15 | IO JAR 的 `io/saasforge/sdk/io/upgrade-import-backup.sql` | 新增 task `import_model`、快照数/截止和带 RLS 的 backup 表；历史模型保留 NULL，快照数为 0，截止为空，不伪造历史快照。 |
| V16 | 同目录 `upgrade-import-rollback-preview.sql` | 记录实际后续覆盖任务，不按创建顺序推断覆盖。 |
| V17 | 同目录 `upgrade-import-rollback-execution.sql` | 新增逐记录/任务回滚状态与计数；历史任务回滚状态为 NONE、计数为 0，原导入状态和行数不变。 |
| V18 | storage JAR 的 `io/saasforge/sdk/storage/upgrade-retention.sql` | 本历史基线已完成 purpose，仍缺 retention 两列；已有源文件和结果对象保持原状态与永久保留语义，不重跑 purpose 迁移。 |

所有 SQL 来源于**消费的目标 JAR**，保留中文表/字段注释及 RLS。原 V1—V14、业务数据、任务总数、状态、行数、时间和结果均保留；不执行 clean/repair，不清库，不将当前完整 `schema.sql` 与增量 SQL 同时执行。IO 基线已经包含死信表，本样例不重复默认工程的死信建表步骤。

## API 与运行时核对

保留业务已有导入/导出路由、权限码与业务模型。该历史基线已经采用三参 `ExcelIo.importFile(model, source, validate)` 和单独 `confirmImport`，因此不需要再改业务入口；更早的四参导入签名须按[接入指南](../business-service-guide.md)调整。升级依赖不能通过关闭检查或覆盖业务模型来处理。

`/io/tasks` 及单任务/下载接口继续仅供提交人使用。历史任务的 `importModel` 保留 NULL；历史待确认任务仍由原业务确认路由选择模型，服务重新校验源文件后写入，不提前伪造模型名或跳过确认。历史成功任务没有快照时，`snapshotAvailable=false`，界面不提供回滚操作。本样例没有覆盖导入与前端，不为验收新增业务功能。

有前端时同步核对 `IoTaskApi.list(page)` 的分页参数与 `Page<IoTask>` 返回值，不再从回调中取 `.items`；保留确认回调与状态刷新。覆盖导入与回滚仅按实际已接入的业务能力合并写入器、预览/执行入口及独立权限；详见接入指南，不因为升级引入原来未使用的功能。

IO 的 storage、audit、scheduling 依赖继续按接入指南配置。保留 Redis `forge:io:<服务名>:tenants`，不要在部署时清除任务租户集合。升级期间暂停业务写入，先在隔离环境确认旧待确认任务可继续、成功任务不重复执行、失败报告和导出文件可下载；不承诺迁移后的数据库回退到旧 SDK。

## 本地与 CI 验收

需要固定候选、JDK 17、Python 3、Docker，以及能解析上述历史提交的 Git 历史。入口不接受外部数据库目标，每轮由既有 SharedContainers 创建独立 PostgreSQL 数据库/Redis 库，另建仅供该类使用的 S3 容器。新证据目录须尚不存在。

```sh
bash scripts/release/candidate.sh use /path/to/fixed-candidate \
  bash deploy/upgrade-smoke/io.sh /path/to/new-io-evidence
```

默认通过 `git archive` 导出固定历史源码。也可在最后追加一个该提交的干净工作树路径；本次人工验收使用 app 管理的独立历史工作树。脚本先构建历史依赖闭包及旧样例，再复制业务源码，升级依赖并追加缺失迁移，构建候选样例，通过真实 HTTP 完成下列检查：

- 旧入口创建成功导入、校验失败、成功导出、待确认导入四个任务及业务订单；失败报告/导出通过实际 S3 预签名 HTTP 下载。
- 旧进程停止后，新 JAR 使用同一数据库/Redis/S3；原任务的状态、行数、时间、报告及导出字节保留，历史模型与快照/回滚默认值符合契约。
- 本人任务可见，其他成员/租户不可见；旧待确认任务在升级后重新校验并确认，重复确认被拒，新增导入与导出正常。
- 原 Flyway 历史完整保留，只新增四条迁移；再次启动历史不变、任务不重置。

诊断保留在证据目录：`build-historical.log`、`build-old.log`、`resolve-candidate.log`、`build-upgraded.log`、`verify-upgraded.log`、三阶段 `runtime-*.log`、`baseline.json`、`http-evidence.json` 及旧/新工程。HTTP 失败时优先查看响应与运行日志，缺列则补增量；checksum 不符则恢复历史文件。验证不会向目标 Maven/npm 仓库上传，不操作共享或生产数据库。

验收使用真实 JWT/JWKS 与 Redis 权限、真实业务 HTTP 和 S3。IAM 字典/租户目录沿用已有测试接缝，MQ/Nacos/远程 Manifest/生命周期写保护关闭；不代替完整 IAM 登录、网关、Shell、真实 Broker、覆盖导入回滚执行或到期清理专项验收。
