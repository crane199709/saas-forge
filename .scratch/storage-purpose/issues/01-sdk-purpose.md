# 01: SDK 用途声明与校验

**What to build:** 按 spec 第 2 节，在 `forge-sdk-storage` 增加 `StoragePurpose` 和校验，并修改 `ObjectStorage` 的接口。

**Blocked by:** —

**Status:** resolved

- [x] 声明时校验编码格式和大小上限，非法声明启动失败
- [x] 扩展名大小写不敏感，不在白名单或超限时返回对应错误码
- [x] 对象表记录用途编码，下载时校验用途
- [x] 下载 URL 时长取自用途
- [x] SDK public API 带文档注释，测试覆盖各错误码和跨租户访问
- [x] 更新 `schema.sql`，提供存量行回填方案

## Comments

- 2026-11-09：工单 01 由 SDK 分支领取；测试边界为 StoragePurpose 声明和 ObjectStorage 公共 API。

## Answer

已增加不可变 `StoragePurpose(code, extensions, maxSize, downloadUrlTtl)`。编码采用最长 64 位的小写短横线格式；`unspecified` 是保留的存量标记，不能声明。扩展名不含点并按大小写不敏感比较；大小为 1 字节至 5 GiB；下载时长为 1 秒至 7 天的整秒。

公共边界为 `StoragePurpose` 声明及 `ObjectStorage`。`prepareUpload`、`write`、`prepareDownload` 接收用途为首参；另有 `get(StoragePurpose, UUID)` 供导入和业务绑定之前校验用途。用途记录在对象表；跨租户始终返回 `storage.objectNotFound`，同租户用途错误才返回 `storage.purposeMismatch`。新增三类错误码及中文文案。

新安装 schema 增加无默认值的 `purpose` 列；已有服务采用 SDK `upgrade-purpose.sql` 复制为新 Flyway 迁移：先以 `unspecified` 回填旧行，再移除默认值。旧对象保留，但普通用途无法下载；调用方只能通过可信业务关联显式归类，未知用途不自动猜测。

### 验证

- TDD 逐片完成声明、扩展名、大小、上传持久化与下载、服务端写入及存量夹具；大小超限测试确认实现前未抛异常，修复后通过。
- `cd backend && ./mvnw -o -pl examples/demo-service -am install -DskipTests`：通过，源码与本地 SDK 制品已同步，并通过相关 Spotless 检查。
- `./mvnw -o -pl forge-sdk/forge-sdk-storage test -Dtest=StoragePurposeTest,ArchitectureTest`：3 项通过，无失败、跳过。
- `./mvnw -o -pl examples/demo-service test -Dtest=ObjectStorageTest`：29 项通过，无失败、跳过；使用真实 PostgreSQL、Redis、S3 兼容容器，覆盖大小写扩展名、错误码、URL 期限、配额、跨租户及旧行迁移。
- 未重复执行模块全量；按集成安排留给全规格交付前统一执行。

### 工单 02 接续

- 临时保留并标记 `@Deprecated(forRemoval=true)` 的无用途 `prepareUpload`、`write`、`prepareDownload` 及旧下载 TTL 配置，只为本票中间版本可编译；02 必须删除。最终不保留这些绕过用途的入口。
- `get(UUID)` / `open(UUID)` 仍可供管理与清理；业务导入及附件确认必须在提交异步任务或绑定对象前调用 `get(purpose, id)`，防止错误用途的源文件被读取或任务 finally 清理。
- 服务正式迁移由02添加。测试暂用 `src/test/resources/storage-purpose-migration/V14__storage_purpose.sql`，内容复制自 SDK 升级脚本；正式服务 V14 到位时删除这个临时测试 V14。
- 保留测试目录的 `V13_1__legacy_storage_object.sql` 及 `ObjectStorageTest` 的额外 Flyway location，使旧格式登记仍在真实服务 V14 前插入，继续通过公共 API 验证真实回填。夹具只位于 `src/test`，不修改生产数据。
