# 文件保留与治理验收

验收日期：2026-11-13。规格：[spec.md](spec.md)，实现取舍：[ADR-0026](../../docs/adr/0026-file-retention-two-phase-delete.md)。票 01–03 已集成至 `9c393b406c452842c392a2ad12bbbbee92ee57a8`；票 04 在该版本上补 demo 生产声明与最终验收。工单与整个规格的关闭由集成审查决定，本记录不代替审查结论。

## 验收接缝与环境

- 使用既定的公开 `ObjectStorage`、`StorageMaintenance.clean()`、`FileReferenceChecker` 接口；可控 `Clock` 只替换时间边界。demo 上传通过真实 HTTP 业务入口获取凭证，并直传真实 S3，再通过 SDK 确认。
- 真实 PostgreSQL 17、Redis 复用 demo `SharedContainers`，每测试类独立数据库/Redis 库；S3 沿用 `ObjectStorageTest` 独占的 S3 兼容容器。只操作隔离容器，无共享开发库或线上对象变更。
- Java 17，Maven Wrapper 离线运行；容器模块沿用默认 `forkCount=2`。日志保存在 `/tmp/saas-forge-file-retention/`，原始日志为本次机器运行产物，不纳入仓库。
- 无新增前端页面；不运行全后端或前端 UI 套件。scheduling 实现未修改，真实 demo Spring 上下文覆盖新任务注册与依赖绑定。

## 行为矩阵

下列无前缀的方法均位于 `backend/examples/demo-service/src/test/java/io/saasforge/demo/ObjectStorageTest.java`；SPI 单测位于 storage 模块的 `FileReferenceChecksTest`，声明校验位于 `StoragePurposeTest`。最终完整套件结果见后文。

| 验收项                         | 公开行为证据                                                                                                                                                                                                                                                                       | 结论             |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------- |
| demo 服务声明 30 天策略        | `demoAttachmentPurposeExpiresAnOrphanAfterThirtyDaysAndReleasesQuotaAfterGrace`：通过真实订单上传入口登记孤儿，11 月 1 日登记的截止为 12 月 1 日；此前可读，到期后不可读；12 月 7 日 23:59:59 仍有对象与容量，12 月 8 日才删除并释放                                               | 新增红→绿通过    |
| 保留期可选、正数校验           | `StoragePurposeTest.retentionIsOptionalAndInvalidRetentionPreventsServiceStartup`；`purposeWithoutRetentionKeepsConfirmedFilesPermanently`                                                                                                                                         | 已覆盖           |
| 历史 STORED/PENDING 迁移       | `prePurposeObjectRemainsRegisteredButCannotBeDownloadedAsAnExplicitPurpose` 配合迁移前 fixture，验证原状态与空截止保留；demo V18 只加新列，不改历史迁移                                                                                                                            | 已覆盖           |
| 到期与七天容量边界             | `retentionDeadlineExpiresWithoutDeletingTheFileOrReleasingQuota`；`cleanupExpiresAnOrphanAndOnlyPurgesAfterSevenDaysReleasingQuota`；显式删除用 `deletingAttachmentStartsGracePeriodAndKeepsQuotaButOnlyWithinOwnTenant`                                                           | 已覆盖           |
| 失效和删除前分别查引用         | `skippedFirstBatchDoesNotStarveLaterOrphansAndPurgeRechecksNewReferences`：首批引用不饿死后批，宽限期新增引用阻止 purge，解除后可继续                                                                                                                                              | 已覆盖           |
| 逻辑删除行仍保护文件           | `fileReferenceChecksIncludeLogicallyDeletedAttachmentsWithoutCrossingTenantsOrPurposes`：普通列表不见附件，EXPIRED 文件在七天后仍有登记、S3 与配额；其他租户查询看不到该引用                                                                                                       | 已覆盖           |
| 缺实现与用途隔离               | `FileReferenceChecksTest.missingOrUncoveredCheckersNeverAuthorizeDeletion`；`missingAndFailingCheckerPurposesKeepTheirFilesWhileAnotherPurposeIsPurged`，跳过仍保持文件可读、对象存在                                                                                              | 已覆盖           |
| 异常、超时、非法结果与安全告警 | `FileReferenceChecksTest.everyCoveringCheckerMustSucceedWithAValidCandidateSubset`、`aTimedOutUninterruptibleCheckerDoesNotBlockAnotherPurposeOrLeakTenants`、`hungCheckersHaveBoundedConcurrencyAndSkipWithASafeDiagnostic`；多检查器取引用并集                                   | 已覆盖           |
| 批量上限与有限并发             | `FileReferenceChecksTest.oversizedBatchesAreNotSentToBusinessQueriesAndInvalidLimitsFailStartup`；超时线程不排队、不无限扩容，容量耗尽跳过告警                                                                                                                                     | 已覆盖           |
| PENDING 与跨租户               | `unconfirmedUploadCanExpireAtItsUploadDeadlineAndRestoreStillNeedsConfirmation`；`cleanupPurgesUnconfirmedAndNeverUploadedReservationsWithoutCrossingTenants`：已上传未确认、从未上传预留均可回收，其他租户不受影响                                                                | 已覆盖           |
| key 与上传凭证边界             | `purgeKeepsForeignKeysAndWaitsForOutstandingUploadCredential`：拒绝跨租户/服务 key；原上传 URL 仍有效则保留，避免晚到 PUT 生成无登记文件                                                                                                                                           | 已覆盖           |
| S3/数据库失败及重试            | `s3FailurePreservesPendingPurgeAndQuotaUntilRetrySucceeds`；`databaseRollbackAfterS3DeletionKeepsRegistrationForAnIdempotentRetry`：失败不提前释放配额，保留可重试状态，成功仅一次 purge 审计                                                                                      | 已覆盖           |
| 恢复、延长与并发               | `expiredFileCanBeRestoredDuringGraceWithAFreshDeclaredRetention`、`retentionExtensionReactivatesAnExpiredFileButCannotShortenOrBypassGrace`；`extensionWaitsForExpiryDecisionThenRestoresWithTheNewDeadline`；`purgeSerializesRestoreAndExtensionAndConcurrentCleanupIsIdempotent` | 已覆盖           |
| 失效、延长/恢复、purge 审计    | `lifecycleAuditsOnlyCommittedChangesAndDoesNotExposeFileContent`：实际提交才记录，幂等调用不重复，回滚不记录成功，不含文件名/内容/凭证                                                                                                                                             | 已覆盖           |
| IO 与附件调用兼容              | 既有 IO 确认/源清理/结果到期及附件删除用例；`unconfirmedImportsExpireAfter24HoursAndRecoveryExpiresTheirSource`、`expiredResultsEnterGraceAndKeepCapacityWhileTaskHistoryRemains` 断言新两阶段语义                                                                                 | 已覆盖           |
| 架构、SQL、标准列、迁移注释    | 两个 SDK 与 demo 的 `ArchitectureTest`；demo `SchemaCommentTest`、`StandardColumnsTest`                                                                                                                                                                                            | 最终 verify 通过 |

## 接入与运行约束

- 服务以 `StoragePurpose(..., retentionDays)` 声明策略，Tenant/Operator 无法覆盖；四参构造永久保留。新对象从申请/写入登记时计时，旧对象的截止为空，不追溯应用新策略。仅有保留期不会授权删除，仍被业务引用的对象自动治理时保持 STORED 与可读。
- `delete` 只标记 EXPIRED，宽限从实际失效时起固定七天；`PENDING`、`EXPIRED`、`PURGE_PENDING` 均占容量。只有 S3 删除成功且登记清除提交后释放配额。原上传凭证仍有效时即便满七天也继续保留；已签发的下载 URL 在原 TTL 内仍可使用。
- 失效和物理删除各查一次引用。SPI 必须完整声明覆盖用途、包含逻辑删除行、保留租户/RLS，引用完整性查询不按成员 Data Policy 缩窄。任一实现缺失、异常、超时或返回非法结果均跳过批次，WARN 仅含 tenant/purpose/phase/reason。跳过不是“无引用”；持续跳过会继续占容量。
- demo 生产只覆盖 `order-attachment`；`io-source`、`io-result`、历史 `unspecified` 无生产检查器。IO 清理可解除任务引用并标记文件失效，但这些对象仍安全跳过物理治理并占容量。测试专用 cleanup 检查器不扩大生产覆盖。今后应由所属模块实现完整引用检查，storage 不反向依赖 IO。
- 新服务复制 storage 当前 `schema.sql`；已有服务新增 Flyway 迁移复制 `upgrade-retention.sql`（demo V18），不改已执行迁移，不运行 repair。现有数据保持 STORED/PENDING 及空截止。`SchemaCommentTest` 覆盖新列中文说明和 starter 表齐备。
- storage 新增 audit/scheduling 直接依赖：除存储配置/表外须准备 MQ 必需表、`forge-audit` Topic、可用 Redis、`forge.scheduling.iam-uri` 与 `forge.scheduling.token`（可分别引用 manifest 的 IAM URI/服务凭证）。`forge-storage-retention` 每小时按试用/正常 Tenant 执行；默认脚手架仍不启用 storage，不预加可选表和配置。
- 审计沿用 `AuditRecorder` 提交后独立事务写 Outbox 的保证：业务回滚不记成功，审计写失败只告警，进程恰在业务提交与审计写入间终止仍可能丢事件，不承诺绝对原子。
- `HELD` 仅预留给未来 Legal Hold；v1 无冻结/解除接口、权限、流程或管理页面。物理删除不可恢复；当前策略用于孤儿存储治理，不是到期强制删业务引用文件的合规删除。

## 命令与结果

所有 Maven 命令的工作目录均为票 04 专属工作树的 `backend/`，前端生成器命令在该工作树根目录运行。

| 命令                                                                                                                                                        | 结果                                                                                                                           | 日志                          |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ----------------------------- |
| `./mvnw -o -pl examples/demo-service -am install -DskipTests`                                                                                               | 成功；定向测试前安装当前 SDK                                                                                                   | `04-initial-install.log`      |
| `./mvnw -o -pl examples/demo-service test -Dtest=ObjectStorageTest#demoAttachmentPurposeExpiresAnOrphanAfterThirtyDaysAndReleasesQuotaAfterGrace`（声明前） | 1 项、1 个预期断言失败：期望 2026-12-01，实际截止为 null                                                                       | `04-demo-retention-red.log`   |
| 同上（声明后）                                                                                                                                              | 1 项通过，0 失败/错误/跳过                                                                                                     | `04-demo-retention-green.log` |
| `./mvnw -o -pl forge-sdk/forge-sdk-storage,forge-sdk/forge-sdk-io,examples/demo-service -am install -DskipTests`                                            | 成功                                                                                                                           | `04-final-install.log`        |
| `./mvnw -o -pl forge-sdk/forge-sdk-storage,forge-sdk/forge-sdk-io,examples/demo-service verify`                                                             | 全部成功：storage 11、IO 1、demo 162，共 174 项；0 失败/错误/跳过，含 ObjectStorageTest 96 项、架构/迁移注释/标准列及 Spotless | `04-full-verify.log`          |
| `node --test frontend/packages/sub-app-kit/bin/create.test.ts`                                                                                              | 2 项通过                                                                                                                       | `04-sub-app-generator.log`    |
| `./mvnw -o -pl forge-service-archetype clean verify -Parchetype-it`                                                                                         | 成功：生成工程 17 项测试及后置规范/大括号门禁脚本通过                                                                          | `04-archetype-generator.log`  |
| Prettier 3.9.10 检查新增验收文档、CHANGELOG 与本次变更的 storage 指南段                                                                                     | 通过；整份指南存在基线格式差异，见下文                                                                                         | `04-docs-format-final.log`    |
| `git diff --check`                                                                                                                                          | 通过                                                                                                                           | `04-diff-check.log`           |

文档初次整文 Prettier 检查提示指南和新验收文档；新增文档已格式化。对 `HEAD:docs/business-service-guide.md` 单独复核也失败（`04-docs-baseline-format.log`），差异为原有表格对齐及多余空行；本票保留该文件其他段落，检查本次变更的 storage 段，不把基线整文格式问题计为通过。

前三票逐切片的红→绿与定向测试日志见同一临时目录下的 `01-handoff.md`、`02-handoff.md`、`03-handoff.md`；本票以最终完整套件复核合并后的行为，不重复新增同类测试。

## 实际验证边界

真实 S3 兼容协议验证使用隔离 silo 容器，未验证 AWS 托管 S3 或其他供应商实际部署；测试直接调用治理任务入口并推进时钟，没有真实等待 30+7 天。demo Spring 上下文覆盖任务注册，未与真实 IAM 目录、RocketMQ broker 做跨服务联调；审计断言覆盖提交后的真实数据库 Outbox，未验证消息最终投递和平台消费。未做生产数据库升级演练、共享对象迁移或前端页面验收。

## 双轴审查修复（2026-11-13）

- 标准审查三项：demo `OrderAttachmentMapper.xml` 的真实批量引用查询增加 `timeout="3"` 秒；`FileReferenceChecksTest` 改为 `Assertions` 类名访问 JUnit 断言；该测试、`StoragePurposeTest` 与 `ObjectStorageTest` 的新增无冲突全限定类型改为显式 import。
- 规格审查的绑定/失效交错：治理在每批事务内先按对象 ID 顺序锁定所有候选，再执行一次批量引用检查并失效/删除；业务建立引用在同一可写事务内先 `get(purpose, id)` 持相同行锁，再插入引用。绑定先提交时检查器能读到真实附件引用，文件继续可读；失效先提交时绑定明确失败。未增加逐对象业务查询，也未用旧引用结果延迟恢复文件。
- demo 保持 `completeUpload` 在新建绑定事务外执行，配额拒绝的失效不会因绑定失败回滚；既有 restore/extend/purge 共用对象锁。无事务或只读事务的带用途 get 保持普通读取；不作为后续绑定许可。
- 业务规范、SDK Javadoc、接入指南、archetype 与 sub-app-kit 模板及 CHANGELOG 同步绑定事务规则与数据库查询超时要求；默认模板仍按需接入 storage。

新增验证仍沿既定公开接缝：`attachmentBindingCannotSucceedAfterExpiryHasConfirmedNoReferences` 用真实 HTTP 上传、真实 S3、真实引用查询的暂停结果与实际 HTTP 附件绑定复现交错；`attachmentCommittedWhileCleanupWaitsRemainsReadable` 经真实 `OrderAttachmentService.attach` 在绑定事务提交前暂停，治理等待提交后，真实 HTTP 下载和 S3 内容仍正确；`purposeCheckedGetStillWorksInAReadOnlyTransaction` 验证只读事务兼容。原延长并发用例改为在失效决定之后取得锁并恢复、延长，最终仍为 STORED 与新截止时间。

最新验证日志位于 `/tmp/saas-forge-file-retention/`，工作目录为专属修复工作树 `backend/`：

| 命令/验证                                                                                                        | 结果                                                                                           | 日志                         |
| ---------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- | ---------------------------- |
| `./mvnw -o -pl examples/demo-service -am install -DskipTests`                                                    | 成功，旧行为复现前安装当前 SDK                                                                 | `review-initial-install.log` |
| `ObjectStorageTest#attachmentBindingCannotSucceedAfterExpiryHasConfirmedNoReferences`（旧实现）                  | 1 项、1 个预期失败：期望 `storage.objectNotFound`，实际 `ok`，确认真实绑定成功与旧空结果的交错 | `review-binding-red.log`     |
| 同一测试（修复后）                                                                                               | 1 项通过，绑定明确失败且没有附件行                                                             | `review-binding-green.log`   |
| 5 项定向交错/只读/延长/恢复/清除验证                                                                             | 全部通过，0 失败/错误/跳过                                                                     | `review-targeted-demo.log`   |
| `./mvnw -o -pl forge-sdk/forge-sdk-storage,forge-sdk/forge-sdk-io,examples/demo-service -am install -DskipTests` | 最终源代码安装成功                                                                             | `review-final-install.log`   |

最终树验证：

| 命令/验证                                                                                       | 结果                                                                                                                                                                      | 日志                             |
| ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------- |
| `./mvnw -o -pl forge-sdk/forge-sdk-storage,forge-sdk/forge-sdk-io,examples/demo-service verify` | 全部成功：storage 11、IO 1、demo 165（含 ObjectStorageTest 99），共 177 项，0 失败/错误/跳过；各模块 Spotless 通过，包含原 restore/extend/purge/completeUpload 与 IO 回归 | `review-full-verify.log`         |
| `./mvnw -o -pl forge-service-archetype clean verify -Parchetype-it`                             | 成功；生成工程 17 项测试及 verify.groovy 后置规范复制/版本/Wrapper/大括号门禁验证通过                                                                                     | `review-archetype-generator.log` |
| `node --test frontend/packages/sub-app-kit/bin/create.test.ts`                                  | 2 项通过，0 失败/跳过                                                                                                                                                     | `review-sub-app-generator.log`   |
| Prettier 3.9.10                                                                                 | 验收文档、CHANGELOG、Sub-App 模板及本次变更的指南/业务规范 storage 段通过                                                                                                 | `review-docs-format.log`         |
| `git diff --check`、`git diff --cached --check`                                                 | 通过                                                                                                                                                                      | `review-diff-check.log`          |

生成工程继承命令行 profile 提示自身不存在 `archetype-it`，但其真实 verify、17 项测试及后置脚本均成功；与票 04 相同，不计为测试跳过。指南/业务规范仅检查本次 storage 段（去除抽取段末分隔空行），没有重排既有无关格式，不宣称整文 Prettier 通过。

上述原票 04 的完整验证作为历史记录保留，本节是事务修复后的新验证。未新增真实 IAM/MQ/供应商部署验证，实际验证边界仍与前节相同；本次只操作隔离 Testcontainers 数据。规格和工单状态的最终关闭仍由集成审查决定。

双轴复核已完成：Standards 剩余硬违规 0 项、无新增需修复的判断性 smell；Spec 剩余发现 0 项，初始绑定/失效 P2 已关闭。复核报告为 `/tmp/saas-forge-file-retention/review-standards-recheck.md` 与 `review-spec-recheck.md`；两者只读检查修复树及验证日志，没有再次执行测试。完整规格仍由 root 合并后统一关闭。
