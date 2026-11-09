# 变更影响预览规格

Status: resolved

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

## 实现与验收

三张票均已解决，实现汇总于 `claude/change-impact-preview`。本次没有新表，新表相关的验收项不适用。

交付前全量验证：forge-iam 230 个、forge-platform 51 个测试通过，含 `ArchitectureTest` 与 Spotless；tenant-app 174 个、platform-console 72 个单元测试通过；Playwright 37 个通过；类型检查与格式检查通过。

未验证：平台到 IAM 的调用在测试中用 HTTP 桩替代，Playwright 也未连接真实后端和网关，因此没有真实服务联调；平台与 IAM 的内部接口字段已逐项核对一致。

实现时确定的口径：

- Plan 预览的受影响 Membership 只计状态正常、且确实失去有效 Permission Code 或所失去 Sub-App 中可见菜单的成员。
- 已删除的 Sub-App 和已下线的 Permission Code 不算失去。
- 新 Plan 有成员配额时，升级也显示实际差额；这类升级在 IAM 不可用时预览失败。详见 [02](issues/02-plan-impact.md)。
