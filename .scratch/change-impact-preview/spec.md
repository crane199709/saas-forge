# 变更影响预览规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

角色和 Plan 的变更会立即影响 Membership（ADR-0005），但操作前看不到影响范围。

目标：在保存前预览影响。预览只提示、不拦截。

非目标：角色停用和删除（没有停用功能，有成员时不能删除）、部门移动、受影响数据行数、存储用量预览、审批和二次授权。

## 2. 角色变更预览

- 覆盖替换权限和替换数据策略。调用方提交拟修改内容，接口只读，使用与修改相同的权限。
- **权限计算净影响：** 成员通过其他角色仍持有，或本来就超出 Plan 而未生效的 Permission Code，都不算失去。
- 返回受影响 Membership 数量、前 20 名和净失去的 Permission Code。
- **数据策略：** 只返回持有该角色的成员，明确说明不判断具体数据行的变化。
- 只统计状态正常的 Membership。

## 3. Plan 降级预览

- 显示失去的 Permission Code 和 Sub-App、成员数与 `memberQuota` 的差额、以及受影响的 Membership 数量。
- 平台经 IAM 内部接口获取成员数据。使用与修改 Plan 相同的权限。
- 不包含存储用量；存储超限继续由上传时的 Quota 校验兜底。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。
