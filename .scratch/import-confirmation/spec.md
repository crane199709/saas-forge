# 导入确认规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

Import Task 校验通过后立即写入，用户没有机会确认写入规模。

目标：校验通过后等待提交人确认，确认后才写入。

非目标：提交后回滚和覆盖导入（SDK 的 writer 是通用回调，现有导入也没有覆盖语义，等真实需求出现再设计）。

## 2. 流程

上传并校验 → 待确认（显示将写入的行数）或失败（显示 Error Report）→ 提交人确认 → 重新校验 → 写入。

- 只有提交人可以确认，否则返回 `io.confirmForbidden`。状态不符返回 `io.taskNotAwaitingConfirmation`。
- 待确认任务不算中断任务，也不占执行队列。
- 24 小时未确认则过期，并由 `IoMaintenance` 清理源文件。

## 3. 确认时重新校验

确认请求可能落到其他实例，或发生在重启之后。现有校验结果写在本机临时文件，`validate` 和 `writer` 回调也只存在于提交时的内存中，都不能复用。

- 确认接口由 Business Service 提供，再次传入模型和回调（如 `confirmImport(taskId, model, validate, writer)`）。
- 确认后从源文件完整重新校验。失败时生成新的 Error Report，一行不写。
- 这样也能发现校验之后才发生的数据变化。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。
