# 资源恢复规格

Status: ready-for-agent

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
