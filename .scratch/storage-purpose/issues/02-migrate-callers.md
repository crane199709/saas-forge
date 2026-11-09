# 02: 迁移调用方

**What to build:** 把 `IoTasks`、`ExcelIo`、`OrderAttachmentService` 和脚手架迁移到 Storage Purpose。

**Blocked by:** 01

**Status:** resolved

- [x] IO 使用 `io-source`、`io-result`，导入只接受 xlsx
- [x] Order 附件声明自己的用途和限制
- [x] 无用途的重载全部删除，迁移覆盖所有接入服务与脚手架
- [x] 前端展示上传被拒的错误提示
- [x] IO 与附件的现有测试通过，并补充被拒场景
- [x] 更新接入指南和 `CHANGELOG.md`

## Comments

- 2026-11-09：工单 02 在 `codex/storage-purpose-callers` 领取并按 TDD 实现；接缝沿用已确认的 IO、附件公开接口、真实 PostgreSQL/S3 和浏览器拒绝提示。

## Answer

所有接入调用均显式传用途首参。SDK IO 共用 `IoStoragePurposes.SOURCE`（`io-source`、xlsx、20 MiB、5 分钟）和 `RESULT`（`io-result`、xlsx、100 MiB、5 分钟）；结果包含导出及错误报告，允许比源文件更大，以容纳更多导出行及错误列。订单附件为 `order-attachment`（txt/pdf/xlsx/png/jpg/jpeg、20 MiB、5 分钟）。数值采用已授权的简单常量方案，没有新增 YAML 或租户配置。

`ExcelIo.importFile` 在 `tasks.submit` 之前执行 `get(SOURCE, id)`；错用途不会创建任务或触发 source 清理。附件在独立确认存储之后、登记业务关联之前执行 `get(ATTACHMENT, id)`，保留超额删除登记必须独立提交的事务语义。下载分别使用 RESULT 或附件用途，不改既有权限与网关边界。

无用途 `prepareUpload`、`write`、`prepareDownload` 及仅供兼容的内部转发方法已删除，`StorageProperties.downloadUrlTtl`、对应 Javadoc/toString 已删除。全仓调用与配置扫描无残留调用；指南和 CHANGELOG 明确记录破坏性变更。唯一已有存储表的 demo 新增正式 V14，删除临时测试 V14；保留 V13.1 旧格式夹具及额外 Flyway location，使真实旧行在正式迁移前插入并经公共 API 验证。IAM/platform/gateway 未接入 storage，不增加空迁移；脚手架仍按需启用 storage，仅在现有 POM 模板补充用途、schema 与升级指引，不强制引入 S3。

前端既有 `ApiError.userTip` 展示逻辑已满足要求，无产品 UI 源码修改。新增 `frontend/e2e/storage-purpose.spec.ts` 的 4 个场景：附件扩展名/大小被拒、导入服务端大小被拒、非 xlsx 客户端拒绝；断言可见提示且不继续 PUT 或业务确认，并收集 console/pageerror、检查页面内容/标题/overlay、保存拒绝截图。Browser plugin not available，使用普通 Playwright；remoteEntry 支持 `FORGE_STORAGE_REMOTE_ENTRY`，默认 5175，不停止或复用用户已有服务。

### 实际验证

- 五轮真实红绿：导入上传、导入错用途、附件上传、附件错用途登记、任务下载非 RESULT。迁移前均观察到接口错误返回 `ok`；最小实现后转绿。
- `cd backend && ./mvnw -o -pl examples/demo-service -am install -DskipTests`：通过，同步当前 SDK 制品。
- `./mvnw -o -pl examples/demo-service test -Dtest=ObjectStorageTest,DatabaseMigrationTest,SchemaCommentTest,ArchitectureTest`：40 项通过，无失败、无跳过；其中 ObjectStorageTest 36 项覆盖真实存储、导入/导出/错误报告、配额、附件、用途错误不创建任务/不误删、跨租户 404、正式 V14 旧行回填、300 秒下载链接。
- `./mvnw -o -pl forge-sdk/forge-sdk-storage,forge-sdk/forge-sdk-io test -Dtest=StoragePurposeTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`：Storage 3 项、IO 1 项通过，无失败、无跳过。
- `./mvnw -o -pl forge-sdk/forge-sdk-storage,forge-sdk/forge-sdk-io,examples/demo-service,forge-service-archetype spotless:check`：通过；`git diff --check` 通过。
- 按编排约定，模块全量、archetype 生成 verify、前端 typecheck/格式和浏览器验收留给主代理在最终集成工作树统一执行。浏览器用 `/private/tmp/storage-purpose-playwright.config.mts` 的独立 5274/5275 端口，运行时设置 `FORGE_STORAGE_REMOTE_ENTRY=http://127.0.0.1:5275/remoteEntry.js`；此处不声称已运行浏览器。

### 最终集成验收

2026-11-09：模块全量、脚手架生成和前端浏览器/类型/格式验收均已完成；两轴审查均无发现。详见 [最终验收记录](../acceptance.md)。
