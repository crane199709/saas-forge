# 存储用途规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

`ObjectStorage` 只校验大小和配额，任何业务都可以上传任意类型的文件，下载链接时长全局统一。

目标：Business Service 用 Storage Purpose 声明每类文件的规则，由 SDK 统一校验。

非目标：保留期和自动过期（暂无第二个使用方，IO 继续由 `IoMaintenance` 清理）、内容嗅探、病毒扫描、Tenant 或 Operator 配置用途。

## 2. 设计

- `StoragePurpose` 是 Java 常量，包含编码、扩展名白名单、大小上限和下载 URL 时长。大小上限不能超过 SDK 的全局上限。不用 YAML，避免随环境变化。
- `prepareUpload`、`write`、`prepareDownload` 都接收类型化的 `StoragePurpose`，不保留无用途的重载。
- 对象表增加用途编码。下载时用途与记录不一致则拒绝，避免业务用错规则或读到其他用途的文件。
- 校验失败的错误码：`storage.extensionNotAllowed`、`storage.fileTooLarge`、`storage.purposeMismatch`。
- 迁移前已有的对象回填为 `unspecified`，只能由迁移后的调用方按明确用途处理。
- IO 声明 `io-source`、`io-result` 两种用途，Order 附件声明自己的用途。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。
