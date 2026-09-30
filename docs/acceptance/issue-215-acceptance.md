# Issue #215：Task 修改、状态流转与删除验收

日期：2026-09-30。范围：[Issue #215](https://github.com/crane199709/saas-forge/issues/215)，父 PRD 为 [#211](https://github.com/crane199709/saas-forge/issues/211)。实现基线为 `f6e345f5e48802e058c641e5b48a8ae934b8d765`，依赖 #214 已关闭。本记录与实现及测试同提交交付。

## 交付与协议

新增 `PUT/DELETE /api/v1/projects/{projectId}/tasks/{taskId}`，使用 Example 自有正式 [OpenAPI](../../examples/project-service/openapi.yaml) 和 Starter Route Catalog。PUT 替换标题、描述及状态，标题和状态必填，省略/null 描述清空；TODO、IN_PROGRESS、DONE 可任意互转或保持。字段边界沿用标题 200/描述 2000 Unicode 码点、非空白标题、NUL 和非字符串拒绝规范。状态必须精确匹配枚举名，拒绝数字、数字字符串及带空白状态；系统及归属字段不可写。

两操作沿用 UUIDv7 Idempotency-Key 与单个正 int64 强版本 `If-Match: "<version>"`；缺失版本为 428，格式非法为 400，过期版本为 409 RESOURCE_VERSION_CONFLICT。SQL 在 RLS 下同时比较父 Project、Task ID 和版本，修改成功递增版本并维护更新时间；DELETE 成功为 204，无响应体。可见父 Project 下子资源不可见为 TASK_NOT_FOUND，父资源不可见为 PROJECT_NOT_FOUND；其他 Tenant 与不存在资源使用相同语义。

写操作复用 `(identity_id, idempotency_key)` 唯一键、24 小时结果重放、事务 advisory lock 和已有过期清理规则。指纹包含方法、规范父子路径、版本及规范化正文；不同接口、版本、正文或 Tenant 不能复用键。先检查幂等重放再操作资源，故成功删除后的同键重试仍返回 204，新键访问已删除对象返回 404。稳定 404/409 保存原 Problem，400 不占键，基础设施故障回滚业务数据及完成记录。

仅新增 [V7 前向迁移](../../examples/project-service/src/main/resources/db/migration/V7__task_versioned_writes.sql)，授予 title/description/status/version/updated_at 的列级 UPDATE 和 DELETE，V1–V6 不变。运行账号仍无法修改 ID、Tenant、Project 或创建时间；既有强制 RLS 和同 Tenant 复合外键继续生效。

## 验收映射

主证据：[ProjectHttpIT](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectHttpIT.java)。契约证据：[ProjectContractTest](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectContractTest.java)。HTTP 测试通过真实 Starter 验签、声明与 Redis 撤销检查获取上下文，运行真实 PostgreSQL 18、正式 bootstrap/Flyway 和受限账号；不手工注入 TenantContext。

| #215 验收项 | 实测证据 |
| --- | --- |
| 正式修改/删除契约、字段及响应 | 契约测试解析公共 schema，核对 9 个正式路由、必填 title/status、枚举、版本头、错误码与无正文 204。HTTP 测试校验边界、系统字段及错误表示。 |
| 所有状态方向和重新打开 | `taskCanBeUpdatedReopenedAndPermanentlyDeletedWithReplay` 覆盖 TODO→IN_PROGRESS→DONE→TODO→DONE→IN_PROGRESS→TODO，核对标题/描述、版本和不可变字段；非法状态及类型被拒绝。 |
| 归属不可变、伪造父子不可访问 | `taskWriteValidationDoesNotReserveKeysOrAllowImmutableInputs` 拒绝系统/归属字段；`taskWritesRejectForeignTenantsParentsAndUntrustedContexts` 验证同 Tenant 错误父子路径和不存在子 ID 均 404，无副作用。 |
| 原子版本检查、竞争唯一成功、冲突恢复 | `taskConcurrentUpdatesHaveOneWinnerAndAllowConflictRecovery` 以两个 Identity、独立连接和真实资源锁证明两个请求均进入竞争，结果恰为 200/409；读取等于胜者、版本仅增一次，败者同键重放，最新版本新键恢复。旧版本删除 409，读取数据不变，最新版本可删除。 |
| 永久删除与重放 | 删除返回空正文 204，GET 和新键 DELETE 为 404，父集合为空；同键 DELETE 重放 204。`taskInfrastructureFailuresRollBackMutationAndReleaseKey` 另用迁移连接核对删除后的物理行不存在。 |
| 修改重放、跨接口冲突、稳定/基础设施失败 | 更新同键返回首结果且不再增版本；省略/null 描述及字段顺序规范化。更改版本/正文、与创建及删除复用键均冲突。稳定 404/409 重放原正文；真实 AFTER UPDATE/DELETE 故障导致 503、数据和幂等记录回滚，同键恢复成功。`taskWritesReturnInProgressThenReplayAndNormalizeOptionalDescription` 验证 PUT/DELETE 处理中均 409 + Retry-After，提交后重放。既有 24 小时过期测试继续回归。 |
| 跨 Tenant 与可信 Starter 上下文 | A/B 各自修改与删除成功；跨 Tenant 变更和重放拒绝且原数据不变。匿名、非法及 Service Token、平台无 Tenant 上下文与伪造 Header/Query/Body 失败关闭。 |
| HTTP、真实持久化与契约集成 | 新数据库依次迁移 V1–V7；`taskRuntimeWritesCannotBypassRlsOrChangeOwnership` 以运行账号无 Tenant WHERE 更新/删除仍仅影响可见 Tenant，外 Tenant 定向写入为 0，缺失/空/非法上下文拒绝或影响 0 行，归属/ID/创建时间 UPDATE 权限拒绝。回滚后上下文不残留。既有账号/RLS/复合外键及单连接提交/回滚检查继续通过。 |

## 执行结果

- TDD 首个 HTTP 测试在实现前返回 405，按预期失败；实现后状态切换及删除重放通过。
- 扩展验证发现数字 status 被当作枚举序号，初次修正使用已移除的 Jackson 配置项导致编译失败，改用 coercion 配置后通过。Spec 审查进一步指出状态空白转换风险；公开 HTTP 补测实证数字字符串被拒绝，但 `" DONE "` 被自动去空白接受，已改为状态类型上的精确 JsonCreator/valueOf 解析并删除临时 Enum coercion。最终测试拒绝整数、数字字符串和带空白状态，且数据不变、键不被占用。
- Example 定向套件：契约 1 个、真实 HTTP/数据库 32 个，共 33 个通过，0 失败、错误、跳过；包括 #212–#214 的现有回归。
- 全仓 `verify`：759 个测试，0 失败、错误、跳过，BUILD SUCCESS。该运行在最后一次精确枚举修正前完成。
- 精确枚举修正后执行 `./mvnw -pl examples/project-service -am verify`：100 个测试，0 失败、错误、跳过，BUILD SUCCESS；包含最终契约 1 个、HTTP/数据库 32 个及 SDK/Starter 等依赖模块全部回归。未再次执行全仓 verify，不将此前完整通过记录表述为最终差异的全仓重跑。
- code-review：Standards 首审 0 发现；Spec 首审 1 项状态输入问题，已修复。两轴复审均 0 未解决发现。

定向命令：

```sh
./mvnw -pl examples/project-service -am verify \
  -Dtest=ProjectContractTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ProjectHttpIT -Dfailsafe.failIfNoSpecifiedTests=false
```

全仓命令：

```sh
./mvnw --batch-mode --no-transfer-progress --fail-at-end verify
```

## 范围与限制

JWKS/服务发现由受控测试设施提供；Starter 安全链、Redis 撤销、PostgreSQL、权限和事务均为真实实现。未执行真实 IAM/Nacos 部署联调、Gateway/Remote、Chrome、Fresh Compose 或生产发布，均不属于本票范围。Project 删除尚未实现，本记录不据此勾选父 PRD #211 的完整 CRUD 或 MVP 阶段 3。
