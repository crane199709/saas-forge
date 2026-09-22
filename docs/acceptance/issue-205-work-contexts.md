# Issue #205：公司工作台与双身份切换

状态：2026-09-22，后端定向验证重新通过，正式 Client 0.4.0 已发布且独立前端安装验证通过；真实 Chrome 业务验收尚未完成，不得据此关闭 Issue。早期记录保留历史事实，最新结果见文末。

## 后端实现

- 正式 v2 契约新增 `selectConsoleContext`，用于首次选择和平台/公司/公司间切换。请求只接受平台目标或 Membership ID；Tenant ID 由权威 Accessible Membership 解析，Platform Role 不授予 Membership。
- 当前上下文及目标权限均重新核验。当前上下文失效结束整个当前 Family；目标无权只拒绝目标，初始凭据禁止选择。
- 切换保留 Identity、Family 和原会话期限，推进 Context Version。相同目标无副作用；切换记录绑定 Slot、幂等键和目标指纹，原键稳定重放，换目标拒绝。
- 先提交数据库上下文、旧 Token 持久撤销和 `SWITCH_PENDING`；在 Slot 锁内确认撤销交付后进入 `CONTEXT_REFRESH_REQUIRED`。这段锁防止并发恢复在另一请求完成并刷新后误撤销新 Token。交付失败保留已提交事实，允许原键恢复或退出。
- 未 refresh 前 session/contexts/新切换拒绝；refresh 只恢复已选择上下文。新选择要求未消费的当前 Refresh Cookie，旧 Cookie 不作为新切换凭据。
- 当前和候选公司响应包含 Tenant Brand Profile；历史 v1 契约和 Flyway 迁移未修改。

## 验证记录

JDK 17。HTTP 用例使用隔离 Testcontainers PostgreSQL 18、Redis 和 Kafka，并经真实 Tenant Access gRPC/数据库查询权威 Membership。没有操作开发数据库或接管开发服务。

已通过：

- `AuthenticationHttpIT#unified*`：8 项，其中新增 3 项，覆盖双身份初选、平台/公司双向切换、公司间切换、Token 撤销、刷新恢复、同目标与原键重放、品牌响应、租户 Token 拒绝平台 API、无权目标、非法字段、过期 revision、幂等键冲突、撤销故障恢复及当前 Membership 失效。既有无权限/初始凭据用例补充选择拒绝。
- `RefreshTokenFamilyTest`：7 项，包含新增的切换不改变 Identity/期限及非法目标/受限会话拒绝。
- `MyBatisRefreshTokenFamilyRepositoryTest`：12 项既有持久化回归。
- `UnifiedConsoleContractTest`：1 项，新 operation 已纳入自动生成的路由目录。
- `RepositoryStandardsTest`：19 项，包含正式契约、路由所有权及凭据边界一致性检查。
- 正式 OpenAPI 生成 TypeScript Client、TypeScript 编译、打包；在仓库外临时目录安装本地 `0.3.0` tarball，检查生成 operation 的路径、方法、If-Match、目标和浏览器管理凭据不被手工注入。
- `git diff --check`。

执行命令：

```sh
mvn -pl saas-forge-services/iam-service,saas-forge-quality-gates -am test \
  -Dtest='AuthenticationHttpIT#unified*,RefreshTokenFamilyTest,MyBatisRefreshTokenFamilyRepositoryTest,UnifiedConsoleContractTest,RepositoryStandardsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl saas-forge-services/iam-service -am test \
  -Dtest=RefreshTokenFamilyTest -Dsurefire.failIfNoSpecifiedTests=false
```

首次 HTTP 验证有一处测试把租户 Token 用在仅平台会话查询并错误期望 200；改为对应租户上下文查询，并另断言租户 Token 调用平台业务 API 返回 403 后通过。一次扩展检查因 Maven 依赖下载 TLS 握手失败未执行，重试后通过。首次 npm pack 在生成/编译成功后被本地缓存写权限阻止，使用临时 npm 缓存完成打包，未改系统权限或依赖版本。

## 尚未完成的交付与验收

- 正式 `@crane199709/saas-forge-api-client@0.3.0` 已于 2026-09-20 发布到 npm；重新构建的来源为提交 `282f3b07c1348a57aef0d3f3cdd589c9e4a557a5`，`dirty=false`，已通过发布检查。registry shasum 为 `5f9e78f10aacb27aa001e170b1da117e2d573194`。此前本地候选不作为正式发布证据。
- saas-forge-web 已精确升级正式 0.3.0，实现公司卡片、工作台、显式切换、跨标签失效通知与确认保护、晚到响应隔离和品牌整体回退。未采用兄弟仓库、file/link 或临时 HTTP 调用。前端细节与本地验证见该仓库 `docs/acceptance/issue-205-work-contexts.md`。
- 兼容后端和受信 HTTPS 入口已就绪；尚需用真实单身份及双身份账号完成 Chrome 业务证据。当前 HTTP 测试不代替真实 Console、Gateway 或全部直达实例验收。
- 未执行完整 CI、完整 AuthenticationHttpIT、Fresh Compose 或其他专项完整矩阵。

## 本机联调阻塞（2026-09-20）

开发者提供的 Audit 日志显示应用已启动，定时任务因 `audit_isolation_deliveries` 缺失失败。只读查询本机 `compose-postgres-1` 的 `audit_db`，确认没有业务表及 Flyway 历史；已有 `audit-migrate info` 显示空 schema、V1–V5 全部 Pending。应用固定关闭 Flyway，IDE 重启不执行初始化。开发者明确选择暂不执行迁移，因此未运行 migrate、repair 或数据重建，也未启停任何后端应用。此结果不构成真实联调通过。

## 联调恢复与入口检查（2026-09-20）

开发者随后确认后端启动成功；只读检查 Audit readiness 返回 UP。独立前端通过原生 `pnpm run dev` 启动。共享 HTTPS Edge 起初停止，其域名证书检查误用本机 LibreSSL 不支持的 `x509 -ext`，导致已有完整四域 SAN 证书被误判。改用 Node 内置 X509Certificate、禁用通配符与 Subject 回退后，同一实际证书检查由失败转为通过，没有更换证书或放宽验证。

入口回归 `node --test consoles/test/local-https-development.test.mjs`：14 项通过（含证书复用、缺域升级、HTTPS 转发、HMR 和未知域拒绝）；1 项失败，原因是旧 consoles 工作区的 Rolldown 1.1.5 原生依赖缺失，不能计作通过。

本机 /etc/hosts 缺少四域映射。开发者已授权添加固定四域到 127.0.0.1；已有安装函数因 sudo 身份验证失败未写入，等待开发者完成系统验证。此前对 Audit 迁移的“不执行”决定未被代理更改。

开发者已完成四域 hosts 映射，并显式安装现有本地 CA 到系统钥匙串。Edge 启动后状态 RUNNING，未跳过 TLS 校验的 Console HTTPS 请求返回 200；IAM、Tenant Access、Entitlement、Audit readiness 均返回 UP。Chrome 已打开真实 Console 登录页；首次加载时 Vite 优化新增依赖并触发重载，随后登录表单正常显示，尚待有效账号登录。

开发者确认没有账号后，授权使用其指定邮箱初始化本机平台管理员。2026-09-20 14:14，通过已有 PlatformAdminBootstrapApplication 执行一次性引导，返回 INITIALIZED；未直接写 SQL、运行迁移或重启后端。随机初始密码仅保存在 Git 忽略目录中的 600 权限文件，24 小时内有效；尚待开发者在已有 Platform 页面完成首次改密。该账号只有平台角色，不作为双身份验收证据。

按锁文件 SHA-512 校验恢复缺失的 Rolldown 1.1.5 原生文件后，正式生成旧 Platform 页面所需 API Client，并通过原生 pnpm run dev 启动首次改密入口；Chrome 已显示其 HTTPS 登录表单。入口回归重跑 15/15 通过（本机监听需在沙箱外运行）；这不替代待完成的账号业务验收。

### 更正：首次改密入口不可用

开发者实际登录旧 Platform 页面得到 HTTP 410 / AUTH_PROTOCOL_RETIRED。本机 IAM 无凭据探针 `POST /api/v1/auth/login` 同样返回 410；ConsoleBrowserRequestFilter 在统一 Console 启用时明确拒绝该旧入口。此前将页面可打开视为可用于首次改密的判断错误，不能以恢复旧协议绕过。当前 v2 OpenAPI 只有 bootstrap、login、session、contexts、refresh、context-selections、logout，没有首次改密 operation；新 Console 仅处理 PASSWORD_CHANGE_REQUIRED 受限状态。管理员创建成功不代表其已能完成正式登录。

核对 GitHub #204，其明确把初始凭据流程留给后续票；#206 实际负责两仓独立验证交接，并非首次改密实现。父 #201 要求保留该能力。当前需补齐统一协议下的首次改密契约、实现和页面，再由开发者完成改密，才能继续以此新管理员准备真实验收。

## 统一 Console 首次改密补齐（2026-09-20）

经开发者确认扩展范围，新增 v2 changeConsoleInitialPassword；复用既有密码策略与初始凭据事务，使用已存在的 PASSWORD_CHANGE 操作记录，无新增迁移、无恢复旧协议。新建接口的真实集成测试覆盖版本拒绝、输入拒绝不消费、改密后旧密码失效、重新登录、原键重放不清 Cookie、同键不同密码拒绝与正式凭据不能使用首次改密接口。统一认证 9 项、路由契约及仓库规范检查通过。Client 升级至 0.4.0，发布与真实浏览器结果需单独记录。

## 2026-09-22 交付复核

- 通过：JDK 17 执行上文定向 Maven 命令，48 项测试全部通过，零失败、零错误、零跳过：统一认证 HTTP 9、RefreshTokenFamily 7、持久化 12、v2 路由契约 1、仓库规范 19。HTTP 使用隔离 Testcontainers PostgreSQL、Redis、Kafka；首次沙箱内运行因 Docker 不可访问失败，在允许访问 Docker 后完整重跑通过。
- 通过：npm registry 已提供正式 Client 0.4.0；独立前端安装包的来源为 `ebff4b338d0c4b39e51488ac1e9b98885318d438`，`dirty=false`。修复前端 manifest 0.4.0、锁文件和已安装包 0.3.0 不一致，锁文件仅升级该 Client；frozen-lockfile 安装及供应链校验通过。
- 通过：独立前端 35 项测试、类型检查、全部改动 TS/Vue 文件 ESLint、生产构建与差异空白检查。
- 初次入口检查：不跳过 TLS 校验的真实 HTTPS 探针中，`https://console.saas.forge.test` 返回 502，API readiness 返回 502 / `UPSTREAM_UNAVAILABLE`。已请求开发者启动或重载兼容后端并提供真实单租户、双身份验收账号；未代替开发者启停后端、修改授权或执行开发数据库迁移。
- 未执行：本轮真实 Chrome 登录、公司选择与双向切换、刷新/多标签/休眠协调、真实失权、路由与 API 越权、品牌及语言/主题/键盘/焦点验收；完整 CI 与 Fresh Compose。不能用自动化测试或构建结果代替这些证据，Issue 六项仍不能整体勾选。

开发者随后确认后端已启动，并提供本机受限凭据目录。独立前端以 `pnpm run dev` 原生启动后，受信 Console HTTPS 返回 200，Gateway 正式 bootstrap 对无浏览器来源探针返回 403。先前 502 已不作为当前阻塞；页面与业务链路仍需 Chrome 验证。
