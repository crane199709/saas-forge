# 变更记录

本项目遵循语义化版本。尚未发布稳定版本，因此下列内容仍归入 Unreleased。

本文只维护面向使用者的里程碑级摘要；逐次改动的完整历史以 Git 提交与 GitHub Issues 为准。以下条目描述仓库当前已有的能力，不代表其中每一项都已通过完整验收或具备生产支持，未完成范围见 [ROADMAP.md](ROADMAP.md) 与开放 Issues。

## Unreleased

### Added

- Maven 多模块骨架：Gateway、IAM、Tenant Access、Entitlement、Audit、`saas-forge-service-discovery` 支持库、契约模块、Java SDK/Starter 与 `saas-forge-quality-gates`。
- 契约与治理：OpenAPI 3.1 v1 契约与兼容性基线、Protobuf 契约、CloudEvents 事件契约与 HTTP 路由目录。
- IAM：浏览器认证（登录、刷新、登出、首次改密、Password Setup Challenge）、双 Browser Session Slot、Tenant Context 选择与切换、OAuth Client 凭据管理与 JWKS 发布。
- Tenant Access：Tenant 创建与生命周期、管理员初始化与密码投递、冻结与恢复、Tenant 品牌档案。
- Entitlement：Plan、Quota Definition 与 Subscription，含激活、恢复与计量幂等。
- Audit：成功事实消费、只追加审计记录与隔离处置。
- 前端：Platform Console 与 Tenant Console Shell 两个 Vue 3 + Element Plus 控制台，共享 `@saas-forge/admin`、`@saas-forge/app-runtime`、`@saas-forge/api-client`、`@saas-forge/i18n`，含 `zh-CN`/`en-US` 国际化基线与跨标签页会话协调。
- 交付与验证：原生本地开发入口、最小 Compose 基础设施拓扑、`deploy/acceptance` 组合验收、Nacos 配置清单、Helm 接入契约、systemd 单元与共享 Dockerfile。

### Changed

#### 浏览器认证 v1 破坏性迁移

- 现有认证路径保持不变，`POST /api/v1/auth/refresh` 与 `POST /api/v1/auth/logout` 的 JSON Body 新增必填 `sessionSlot: PLATFORM | TENANT`；登录继续使用 `contextType` 表达意图，两个官方 Console 显式提交宿主固定值。
- Refresh Cookie 拆分为 API Origin 签发的 `__Host-sf_platform_refresh` 与 `__Host-sf_tenant_refresh`，分别承载 Platform 与 Tenant 槽位；均为 host-only、Secure、HttpOnly、SameSite=Strict、Path=/，不设置 Domain。刷新和登出只操作所选槽位。
- `platform.<root>` 只能提交 Platform Intent/Slot，`console.<root>` 只能提交 Tenant Intent/Slot；浏览器请求须满足受控 Origin、JSON Content-Type、`X-SF-CSRF: 1` 与 Fetch Metadata 校验。Origin、Cookie 和 Fetch Metadata 仍由浏览器管理，调用方不得伪造。
- 旧 `__Host-sf_refresh` Cookie 将被清除，旧 Refresh Token Family 不迁移，也不并行支持旧单槽位协议；原有浏览器会话需要重新登录。
- **外部消费者中断风险**：依赖旧 Cookie、缺少 `sessionSlot` 或不符合来源与槽位配对要求的现有 v1 客户端可能无法继续认证、刷新或登出。外部消费者须同步升级生成 Client 和调用协议，不能将本次变更视为向后兼容。Gateway、IAM、生成 Client、两个 Console、E2E 脚本与全部第一方消费者须原子升级。
- 本次仅适用 [ADR 0038](docs/adr/0038-browser-sessions-use-intent-bound-slots.md) 批准的浏览器认证 v1 例外；历史兼容基线保持不变，其他 v1 契约不获得豁免。协议与迁移要求见 [Console 认证 Runtime 与浏览器会话规格](docs/28-console-authentication-runtime.md)。

#### Maven 坐标跟随 GitHub 账号改名

- 公开 Maven groupId 由 `io.github.crane0927` 改为 `io.github.crane199709`，根父 POM、`saas-forge-bom`、SDK 与 Starter 的坐标同步变更；artifactId、Java 包名 `io.saas.forge.*`、公开契约与运行行为不变。
- 本次改名发生在本项目向 Maven Central 发布任何版本之前：旧坐标与新坐标在 Central 上都没有制品，也不存在 `v*.*.*` 发布标签，因此没有需要兼容的已发布版本。旧坐标保留但不再发布。
- 依赖片段、`pom.xml` 元数据与文档链接已同步更新。规则依据 [ADR 0053](docs/adr/0053-maven-groupid-follows-github-account.md)；新命名空间注册与发布 Secrets 的核对步骤见 [Maven 构建与制品发布](docs/21-maven-build-and-release.md)。
