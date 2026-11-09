# 资源恢复规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

标准表统一逻辑删除（ADR-0016），但误删后用户无法自行恢复。

目标：指定资源支持查看已删除的记录并 Restore。

非目标：集中回收站、彻底删除、新增 `deleted_at`、恢复 Membership 和 Identity（恢复可能意外重新授予权限）。

## 2. 总体设计

- 恢复规则因资源而异，由**各资源自行实现**。SDK 只提供统一错误码、列表的“已删除”视图和接入指南。
- 沿用各资源原有的管理权限；与删除使用同样的锁。
- 删除时间取 `updated_at`，操作人取 `updated_by`，详细历史由 Audit Event 回答。
- 恢复与现有部分唯一索引冲突时，返回 `resource.restoreConflict`，提示先处理冲突记录。沿用现有唯一性规则：Role 只检查同租户编码，Department、Position 在同租户内允许重名，不额外增加名称冲突限制；不同租户互不构成冲突。

## 3. 各资源规则

- **Role：** 删除时不再连带删除授权和数据策略，恢复后配置完整。删除前已要求无成员，因此恢复不会重新授予任何人权限。本功能上线前已删除的角色，恢复后配置为空。
- **Department：** 父部门必须未删除，否则返回 `resource.restoreBlocked`。按当前父部门重算路径，因为父部门可能已被移动。根部门没有此限制。
- **Position：** 只检查冲突。
- **Order：** demo-service 示例，作为 Business Service 的接入范本。

审计：`role.restore`、`department.restore`、`position.restore`、`order.restore`。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。

## 实现与验收（2026-11-09）

已在本地集成分支 `codex/resource-restore-integration` 完成全部规格，未推送。实现基线为 `072edf8`，审查修正提交为 `4018d59`。

- [01：SDK 与指南](issues/01-sdk-shared.md)、[02：IAM 资源](issues/02-iam-resources.md)、[03：demo Order](issues/03-demo-order.md)、[04：租户端界面](issues/04-restore-ui.md) 均为 `resolved`，各工单 Answer 记录接口契约、TDD 与定向验证证据。
- 沿用用户确认的唯一性规则：Role 仅检查同租户编码，Department、Position 同租户允许重名；Order 按原 Business Number 唯一索引判断冲突。
- 没有新增表、恢复权限码、集中回收站或彻底删除接口。Membership、Identity 不提供恢复。

| 验证范围 | 最终结果 |
| --- | --- |
| SDK core / web 模块全量 | 29 + 35 项通过，Spotless 通过 |
| IAM 模块全量 | 238 项通过，Spotless 通过 |
| demo-service 模块 | 83 项最终通过；首次全量的 9 个上下文启动错误经修复后，四个失败类与 OrderRestoreTest 共 18 项定向重跑通过，含真实 MQ 故障恢复 |
| 全前端单元测试 | 273 项通过：sub-app-kit 6、UI 18、platform-console 72、tenant-app 177 |
| 全前端类型、格式、生产构建 | `pnpm typecheck`、`pnpm format:check`、`pnpm build` 全部通过 |
| 恢复流程 Playwright | 6 项通过，覆盖四类恢复、冲突、父级受阻、权限、选择集合隔离与分页 |

后端全量命令为 `./mvnw -o -pl forge-sdk/forge-sdk-core,forge-sdk/forge-sdk-web verify` 和安装最新上游依赖后的 `./mvnw -o -pl forge-iam,examples/demo-service verify`。首次 demo 回归暴露新构造参数强制依赖 Web 操作人提供者的问题；`0e67a4e` 改为可选提供者，系统上下文写 NULL，HTTP 操作人语义仍由恢复测试验证。集成工作区的 demo 制品已重新构建并安装。

### Standards

审查 `git diff 072edf8...HEAD`，发现 2 类 P3 文档问题：RoleMapper 未统一说明 Data Policy 过滤语义，以及两处新增注释使用英文。`4018d59` 已补齐说明并翻译注释；Spotless、Prettier、差异检查通过。判断性代码异味 0，无遗留问题。

### Spec

发现 0 项规格偏差。已核对角色配置保留和无成员恢复、部门依赖及路径重算、原权限与锁、租户与 Data Policy（含恢复 UPDATE）、唯一冲突、审计，以及页面错误、刷新、选择集合隔离和分页。

### 验证边界

- Playwright 使用有状态 HTTP 模拟接口，真实运行页面、路由、权限和联邦加载；未执行浏览器直接连接真实后端的完整恢复流程。后端隔离、约束与并发由集成测试覆盖，截图路径见工单 04。
- 390px 窄屏受到原有 Shell 固定侧栏挤压，未记为移动端视觉通过；桌面 1280×900 交互与视觉通过。
- 上线前已删除角色的授权和 Data Policy 已被旧删除逻辑清除，恢复后配置为空，不补造历史配置。
