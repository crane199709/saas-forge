# Issue #214：Task 创建与查询验收

日期：2026-09-30。范围：[Issue #214](https://github.com/crane199709/saas-forge/issues/214)，父需求为 [#211](https://github.com/crane199709/saas-forge/issues/211)。基线 `f79b36cf6d0280a21cf4f5c47655a9b2d5038818` 已交付 #212；本记录与实现、测试同提交交付。

## 交付范围

[Project Example](../../examples/project-service/README.md) 新增 `POST/GET /api/v1/projects/{projectId}/tasks` 与 `GET /api/v1/projects/{projectId}/tasks/{taskId}`。本票不新增 Task 修改/删除或 Project 列表/修改/删除；合并前更新已保留 master 上 #213 的 Project 列表/修改，不涉及 Gateway、Remote、Console、Permission、Feature 或 Quota。

[OpenAPI](../../examples/project-service/openapi.yaml) 固定标题 1–200 Unicode 码点且非空白、可选描述最多 2000 码点、拒绝 NUL/系统字段/非字符串输入。标题可重复。创建固定 TODO，返回 UUIDv7、同 Tenant 的父 Project、版本 1、三位毫秒 UTC 时间及 Location。详情拒绝父子标识不匹配。

Task 使用 ID 升序 keyset 分页，默认 50、最大 100。游标沿用仓库既有编码约定，绑定 Tenant、父 Project 与排序，24 小时过期；非法/过期/范围不匹配返回 400。末页 nextCursor 为 null 且 hasMore 为 false。

仅新增 [V6 迁移](../../examples/project-service/src/main/resources/db/migration/V6__tasks.sql)，保留 V1–V5。Task 启用并强制 RLS、USING/WITH CHECK，复合外键 `(tenant_id, project_id)` 引用 Project，使用 RESTRICT 而非级联删除。运行账号非 owner、无 BYPASSRLS/角色继承和 migrator 切换能力，对 Task 仅有 SELECT/INSERT，无法修改归属。

Task 与 Project 复用 `(identity_id, idempotency_key)` 键空间、事务锁与完成记录。指纹包含规范方法、父 Project 路径与规范化正文；不可见父资源的 404 为真实稳定业务失败，同事务保存原 Problem 响应。400 不占键，未提交基础设施失败回滚；跨 Tenant 键冲突不返回旧正文。原有过期维护策略继续有效。

## 验收映射

主证据为 [ProjectHttpIT](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectHttpIT.java)，契约证据为 [ProjectContractTest](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectContractTest.java)。主测试经真实 Tomcat/Starter 认证链、签名 Token、Redis 撤销检查、事务上下文和受限 PostgreSQL 账号执行，不手工注入 TenantContext。

| 验收项 | 证据 |
| --- | --- |
| 正式契约与标题/描述边界 | 契约解析公共 Schema、核对全部七个 route（含 master 的 Project 列表/修改）；HTTP 验证重复标题、可选描述、Unicode 上限、超长、空白、NUL、非字符串、非法 JSON 与不可写字段。 |
| 固定 TODO、系统 ID/时间/版本 | `tenantCanCreateAndReadTaskWithFixedInitialStateAndReplay` 与 `taskFieldsAndSystemInputsFollowContractWithoutReservingInvalidKeys` 验证 201、Location、读取相同资源、UUIDv7、版本 1、UTC 时间，创建拒绝任何 status 字段（含 TODO）。 |
| 现存同 Tenant 父 Project、失败无 Task 副作用 | `taskParentsAndTenantIdentityCannotBeForgedAndFailuresReplay` 验证不存在/其他 Tenant 父资源均 404；原父集合数据不变，失败重放相同状态/正文且没有 Location。 |
| 强制 RLS、复合外键、无级联 | `taskRlsAndCompositeForeignKeyProtectDirectRuntimeAccess` 通过真实运行账号证明无 Tenant WHERE 的 SELECT 仍隔离；跨 Tenant 插入被拒绝，伪装本 Tenant 引用其他 Tenant/不存在 Project 得到外键失败。归属 UPDATE 权限被拒绝，运行账号不能禁用 RLS 或切换 migrator。迁移账号删除含 Task 父资源也被 RESTRICT 拒绝。 |
| 分页默认/最大/稳定排序与游标范围 | `taskPagesHaveStableOrderAndRejectForeignOrInvalidCursors` 经 HTTP 创建 101 条同名 Task；默认 50、最大 100、limit=1、跨页无重复遗漏、末页 null/false、空集合、非法/空/重复 limit、非法/过期/超长/跨 Tenant/跨 Project 游标与未知参数。 |
| A/B 自有创建/列表/详情与父子隔离 | 两 Tenant 通过签名 Token 各自创建/读取/列出；跨 Tenant 详情/列表/创建均拒绝，本 Tenant 内父子 ID 不匹配和不存在 Task 也不可访问。 |
| 幂等重试与共享键唯一性 | 同键相同请求重放正文/Location；变更正文、变更父 Project、与 Project 双向复用键、同一 Identity 切换 Tenant 均 409，不返回外 Tenant 数据。稳定 404 保存原响应，23 小时重放/25 小时重新创建，未提交失败不占键。 |
| 真实链路、失败关闭及事务上下文 | 匿名、平台、Service Token、非法 Token、伪造 Tenant Header/Body/Query 均拒绝。Task 直接数据库访问的缺失/空/非法上下文关闭；应用池为单连接，校验 PID 相同，Task 创建提交与注入写故障回滚后无上下文无可见数据，A/B 切换无泄漏。既有 Project 的签名、撤销及同键处理中并发测试继续回归。 |

## 执行结果

- TDD 首次公开 HTTP 测试：预期失败，尚无 Task route，返回 404。后续发现并修复动态 SQL UUID 参数映射、显式空 limit 被默认值接受的问题；契约泛型与字段顺序、RESTRICT SQLSTATE 测试断言也已校正。
- Example 定向完整验证：契约 1 个、HTTP/数据库 16 个，全部通过，0 失败/错误/跳过。随后补充稳定失败的 W3C Trace Context 与重放测试，最终结果见下列完整验证。
- 根项目完整 `verify`：执行 744 个测试，1 个失败、0 错误/跳过；其余 Reactor 模块（含 Starter 与质量门禁）全部通过。唯一失败为新 Trace Context 测试误用 34 位 Trace ID；业务正确拒绝该无效值并生成新 ID，已将测试输入校正为规范 32 位。完整运行本身仍记录为失败。
- 修正后重跑 Example 全部验证：契约 1 个、真实 HTTP/数据库 17 个，共 18 个全部通过，0 失败/错误/跳过；含稳定 404 继承首请求 Trace ID 与重放原正文。没有再次执行全仓完整测试，不将定向恢复表述为单次完整 verify 全绿。
- code-review：Standards 与 Spec 两轴独立审查均 0 未解决发现；Trace Context 补充变更的两个轴复审也均 0 发现。

定向命令：

```sh
./mvnw --batch-mode --no-transfer-progress -pl examples/project-service -am verify \
  -Dtest=ProjectContractTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ProjectHttpIT -Dfailsafe.failIfNoSpecifiedTests=false
```

完整命令：

```sh
./mvnw --batch-mode --no-transfer-progress --fail-at-end verify
```

## 范围与限制

认证的 JWKS/服务发现使用受控测试设施；Starter 验签、声明、Redis 撤销、PostgreSQL 18、正式 bootstrap/Flyway 和运行时权限边界均为真实实现。没有以 Mock Controller、手工 TenantContext 或 SQL 字符串检查替代主要验收。

未执行真实 IAM/Nacos 部署联调、Chrome/浏览器、Gateway/Remote、完整 Fresh Compose 或生产发布，均不属于本票范围。游标不承担认证或授权，隔离仍由可信上下文、父子路径与 RLS 实施。Task 后续修改/删除需要相应前向迁移及验收，本次不提前授予 UPDATE/DELETE。

本记录仅覆盖 #214，不据此勾选父 PRD 的完整 CRUD 或 MVP 阶段 3。

## PR #217 合并前更新

master 合入 #213 后，保留其 Project 列表、版本修改、契约、权限及测试，并与 Task 三个入口合并。Project 的 V5 迁移不变；Task 原 V5 仅在隔离测试库执行过且未合并，用户明确授权本次例外改名为 V6，SQL 内容保持不变。旧 Task V5 已执行的数据库不能直接复用此迁移链；本次迁移验证使用全新隔离测试库，不执行 repair 或修改历史记录。

更新后使用正式 bootstrap、V1–V6 和全新隔离 PostgreSQL/Redis，契约 1 个及真实 HTTP/数据库 25 个共 26 个全部通过，0 失败/错误/跳过；保留 #213 与 #214 全部场景。首次重跑因 target/classes 遗留旧 V5 构建副本被 Flyway 拒绝，移除该构建副本后通过，未修改数据库历史。冲突解决的 Standards 复审发现验收表仍写五个 route，已修正为七个；Spec 复审无发现。完整远端 CI 以 PR 最新提交结果为准。
