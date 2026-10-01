# Issue #216：Project 永久删除、父子竞争与 PRD 覆盖

日期：2026-10-01。范围：[Issue #216](https://github.com/crane199709/saas-forge/issues/216)，父 PRD 为 [#211](https://github.com/crane199709/saas-forge/issues/211)。实现前基线为 `643904551e91399316145c04bb88bddf7d205716`。本记录与实现、测试同提交交付；不声明 GitHub Issue 已关闭或远端 CI 已通过。

## 交付

正式 [OpenAPI](../../examples/project-service/openapi.yaml) 和 Starter Route Catalog 新增 `DELETE /api/v1/projects/{projectId}`。沿用 UUIDv7 Idempotency-Key 与强版本 `If-Match: "<version>"`。删除空 Project 返回 204，无响应体；不存在/外 Tenant 均为 404 PROJECT_NOT_FOUND，过期版本为 409 RESOURCE_VERSION_CONFLICT，存在任意状态 Task 为 409 PROJECT_NOT_EMPTY。版本冲突优先于非空拒绝。无软删除、回收站、恢复和级联删除。

删除事务先校验可信上下文与幂等结果，再取得 Project 的 FOR UPDATE 行锁；Task 创建取得父 Project FOR KEY SHARE，锁持有至业务和完成记录一起提交。删除在取得锁后使用独立语句读取子资源，避免等待后沿用旧快照漏掉刚提交的 Task。创建先提交则删除稳定拒绝，删除先提交则创建稳定 404，不将合法竞争转换为数据库 503。既有同 Tenant 复合外键 RESTRICT 保留为最终约束。

同键成功删除在资源检查前重放 204；稳定 404、非空/版本 409 保留首次 Problem，后续资源状态变化不改变重放。跨 Tenant 键冲突不暴露原正文，基础设施失败回滚业务和键。新增 [V8](../../examples/project-service/src/main/resources/db/migration/V8__project_deletion.sql) 只授予 project_app 对 projects 的 DELETE，V1–V7 不变；RLS、归属列权限及角色隔离保持既有约束。

## #216 验收映射

以下方法均在 [ProjectHttpIT](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectHttpIT.java)；主入口为真实 HTTP，贯穿 Starter 签名/声明/Redis 撤销验证、PostgreSQL 18、正式 bootstrap/Flyway 与受限运行账号。JWKS 和服务发现是受控认证设施，未手工注入 TenantContext。

| 验收项 | 直接证据 |
| --- | --- |
| 正式永久删除契约与空响应 | ProjectContractTest 核对 10 个路由、必填版本和幂等头、删除响应、无请求体 schema 和空 204；HTTP 核对真实空正文。 |
| 非空、DONE 也拒绝，清空后删除 | `projectDeletionRequiresEmptyProjectAndCurrentVersionAndReplaysResults` 核对父子正文不变，Task DONE 后仍拒绝，正式 Task DELETE 后 Project 可删除。 |
| 原子版本检查、恢复 | 同一测试用另一 Identity 修改 Project，旧版本 DELETE 稳定冲突且正文不变；读取版本 2 后以新键删除。既有 Project 两 Identity 并发修改测试继续回归。 |
| 并发父子完整性 | `concurrentTaskCreationAndProjectDeletionSerializeInBothOrders` 通过隔离数据库触发器及 advisory lock 暂停已执行的首请求，观察两个 HTTP 请求在独立连接等待锁，确定性覆盖 201/409 和 204/404 两种顺序；HTTP 核对父子结果，直接数据库核对无孤立 Task、删除先完成时无 Task 行。 |
| 重放、不可见与 Tenant 切换 | `projectDeletionRequiresEmptyProjectAndCurrentVersionAndReplaysResults` 核对成功、版本冲突与非空拒绝重放；`projectDeleteValidationAndTenantSwitchCannotExposeOrRemoveForeignData` 覆盖 A/B 各自删除、跨 Tenant 删除/键重用、首次外 Tenant 404 重放、不同接口或版本复用键冲突和原数据不变。 |
| 数据库外键与权限 | `projectRuntimeDeletionRespectsRlsAndRestrictiveForeignKey` 用运行角色执行无 Tenant WHERE 的 DELETE：B 只能删除自身行（回滚验证），缺失/空上下文影响 0 行，非法上下文拒绝，A 含 Task 时 RESTRICT 拒绝整条删除；迁移角色也无法删除非空父资源。禁止 TRUNCATE CASCADE、删除外键、切换迁移角色；回滚后上下文清空。 |
| 稳定/处理中/基础设施失败 | `projectDeletionReturnsInProgressAndThenReplaysCommittedResult` 证明处理中 409 与 Retry-After: 1，提交后空 204 重放；`projectDeleteInfrastructureFailureRollsBackDataAndReleasesKey` 在真实 AFTER DELETE 触发失败，503 后父行仍在、无完成记录，同键恢复成功。 |
| 验证、说明与父 PRD 覆盖 | 本记录执行结果与下方 13 项映射；Example README 更新用法与边界。 |

## 父 PRD #211 的 13 项覆盖

前序记录：[#212](issue-212-acceptance.md)、[#213](issue-213-acceptance.md)、[#214](issue-214-acceptance.md)、[#215](issue-215-acceptance.md)。历史执行结果只描述各票时点；以下同名测试在本轮 Example 套件重跑，具体结果见下一节。

| # | PRD 验收要求 | 直接测试证据 |
| --- | --- | --- |
| 1 | Project/Task CRUD、字段、重复名称、可选描述、系统字段、分页 | `tenantCanCreateAndReadProject`、`validationDoesNotReserveKeyAndRejectsWritableSystemFields`、`projectPagesAreStableTenantScopedAndValidateCursors`、`defaultAndMaximumPagesHaveCorrectTerminalAndEmptyResults`；Task 对应 create/read、fields、pages、update/delete 测试，以及本票 Project delete。 |
| 2 | TODO 初始状态、所有转换与重新打开，非法状态/归属/不存在父资源拒绝 | `tenantCanCreateAndReadTaskWithFixedInitialStateAndReplay`、`taskCanBeUpdatedReopenedAndPermanentlyDeletedWithReplay`、`taskWriteValidationDoesNotReserveKeysOrAllowImmutableInputs`、`taskParentsAndTenantIdentityCannotBeForgedAndFailuresReplay`。 |
| 3 | 非空拒绝、清空后删除、永久删除不可读 | 本票 `projectDeletionRequiresEmptyProjectAndCurrentVersionAndReplaysResults`。 |
| 4 | 并发 Task 创建/Project 删除、无孤立或级联 | 本票 `concurrentTaskCreationAndProjectDeletionSerializeInBothOrders` 与 `projectRuntimeDeletionRespectsRlsAndRestrictiveForeignKey`。 |
| 5 | 同旧版本修改唯一胜者、过期删除拒绝与恢复 | `twoIdentitiesCompetingForOneVersionHaveExactlyOneWinner`、`taskConcurrentUpdatesHaveOneWinnerAndAllowConflictRecovery`、Task 删除版本测试及本票 Project 删除版本测试。 |
| 6 | 幂等副作用、键冲突、处理中、稳定/基础设施失败 | Project/Task retry、in-progress、stable failure、24-hour expiry、infrastructure rollback 测试，以及本票 delete replay/in-progress/rollback。 |
| 7 | A/B CRUD 与跨 Tenant 列表/详情/写入/创建 Task 隔离 | `tenantsAreIsolatedAndUntrustedCallersCannotReadOrWrite`、`tenantSwitchCannotListReferenceUpdateOrReplayForeignProjects`、`taskParentsAndTenantIdentityCannotBeForgedAndFailuresReplay`、`taskWritesRejectForeignTenantsParentsAndUntrustedContexts` 及本票 A/B delete。 |
| 8 | 同 Identity 切换 Tenant，ID/游标/幂等不能越界 | Project tenant-switch、Task parents/pages/writes 测试及本票 delete tenant-switch 使用相同 Identity 的不同 Tenant 签名 Token。 |
| 9 | 匿名/无上下文/平台/Service/伪造输入/无效 Token 失败关闭 | `tenantsAreIsolatedAndUntrustedCallersCannotReadOrWrite`、`signaturesRevocationAndCanonicalResourceIdsAreEnforced`、Task parents/writes 与本票 delete validation。 |
| 10 | 运行角色无租户筛选读写仍隔离，跨 Tenant 插入/归属/复合外键失败 | `runtimeRoleCannotBypassRlsOrWriteAcrossTenants`、`databaseUpdatePrivilegesPreserveRlsAndImmutableOwnership`、`taskRlsAndCompositeForeignKeyProtectDirectRuntimeAccess`、`taskRuntimeWritesCannotBypassRlsOrChangeOwnership` 与本票 direct delete。 |
| 11 | 缺失/空/非法数据库上下文与角色绕过拒绝 | 上述直接运行账号测试与本票 direct delete，核对非 owner、无 super/BYPASSRLS/继承、禁止 SET ROLE/DDL/TRUNCATE。 |
| 12 | 同一连接提交/回滚后切换 Tenant/无上下文不泄漏 | `samePooledConnectionClearsContextAfterCommitAndInfrastructureRollback` 与 `taskTransactionsClearContextOnCommitAndRollbackAndExpireResults` 强制池大小 1 并核对 backend PID；本票补删除回滚后的无上下文查询。 |
| 13 | 新数据库迁移、正式契约与相关模块检查，范围一致 | 本轮新数据库迁移 V1–V8，ProjectContractTest 与相关模块 verify；全仓结果见下。 |

## 执行结果

- TDD：首个公开 HTTP 删除测试在实现前返回 405，按预期失败；实现后通过。扩展验证先发现契约缺少 projectId 参数，已补齐；直接数据库测试误把 RESTRICT 拒绝写为外键插入 SQLSTATE，已按实际 PostgreSQL RESTRICT 语义修正为 23001 后重跑通过。
- `./mvnw -pl examples/project-service -am verify`：106 个测试通过，0 失败、错误、跳过；Example 契约 1 个、真实 HTTP/数据库 38 个，包含 #212–#215 的全部现有回归及本票 6 个测试；依赖模块包含 Starter 的 TenantContext 与真实 Redis 认证回归。JDK 17。
- 最终代码全仓 `verify`：754 个测试，753 通过、1 失败、0 错误/跳过，BUILD FAILURE。唯一失败为 `RepositoryStandardsTest.persistenceArtifactsStayInsideOwningService` 扫描既有且未跟踪的 `.scratch/issue-206/history-source/.../AdministratorPasswordSetupMapper.xml`，将历史备份判为正式持久化源码越界；本轮 Example 契约 1 个、真实 HTTP/数据库 38 个全部通过。质量模块的 11 个集成测试因该模块单元测试失败未执行。
- 补验：使用当前已跟踪文件的工作区内容及本票新增 V8/验收记录复制形成隔离源码视图，排除 `.scratch/`，构建产物沿用本轮真实 target；原备份没有移动、修改或删除。以 `-DrepositoryRoot=<隔离源码视图>` 执行质量模块及依赖 Reactor，限定质量模块的 3 个单元测试类和 4 个集成测试类：33 个测试通过，0 失败、错误、跳过，BUILD SUCCESS。其中 22 个单元测试包含首轮失败项，11 个集成测试包含真实 PostgreSQL 数据边界、基础设施、SDK 发布边界及覆盖率门禁。
- 未在含原备份的工作区再次执行全仓 verify，不能将以上分段补验表述为单次全仓 BUILD SUCCESS。正式源码门禁补验已通过；现有扫描器会读取 `.scratch` 的行为没有在本票扩大修改。远端 CI 未执行。
- code-review：Standards 0 硬违规、1 可选命名建议（Task 删除 helper 已复用于 Project，已改为 deleteResource）；Spec 0 发现。无未解决行为问题。
- 文档相对链接和 `git diff --check` 通过；已发布 V1–V7 无差异。

补验命令（`repositoryRoot` 指向上述当前源码隔离视图，不包含本机临时备份）：

```sh
./mvnw -pl saas-forge-quality-gates -am verify \
  -Dtest=RepositoryStandardsTest,V1ContractCompatibilityTest,JavaSdkOpenApiPublicationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=InfrastructureContainersIT,PostgreSqlDataBoundaryIT,JavaSdkReleaseBoundaryIT,CoverageThresholdIT \
  -Dfailsafe.failIfNoSpecifiedTests=false -DrepositoryRoot=<隔离源码视图>
```

全仓命令：

```sh
./mvnw --batch-mode --no-transfer-progress --fail-at-end verify
```

## 范围与限制

本票只交付 Project/Task 后端 API 与租户隔离；未执行真实 IAM/Nacos 部署联调、Gateway/Remote、Chrome、Fresh Compose 或生产发布。它们属于范围外、未执行，不是已通过或测试框架跳过。未使用 Mock 业务/数据库或手工 TenantContext 注入作为主验收。父 PRD 的后端 13 项覆盖不代表 MVP 阶段 3 或 Permission/Feature/Quota、正式 Client 发布与最终产品验收完成。
