# 03: Tenant Realm 最小登录链路

**What to build:** 打通认证的最细一条路径：一个预置的 Identity（属于一个预置 Tenant、持有一个 Membership）在 tenant-app 用账号密码登录，iam（基于 Spring Security 内置 Authorization Server，ADR-0004）签发 Access Token 与 Refresh Token；请求经网关校验 Tenant Realm issuer 后到达 demo-service，demo-service 经 SDK 用 JWKS 本地验签，返回当前 Identity、Tenant、Membership；tenant-app 显示当前用户与租户。

**Blocked by:** 01 工程骨架与版本组合验证

**Status:** resolved

- [x] iam 以 Flyway 迁移建立 Identity、Tenant（最小字段）、Membership 的表结构与预置数据
- [x] 账号/邮箱 + 密码登录成功，Access Token（约 15 分钟）claims 含 `sub`、`tenant_id`、`membership_id`、`ver`
- [x] 错误密码、未知账号返回统一的认证失败响应
- [x] Refresh Token 可换取新的 Access Token
- [x] 网关拒绝无 Token、伪造签名或 issuer 不符的请求
- [x] demo-service 引入 SDK 安全 starter 后无需手写配置即可验签，并能在业务代码中读取当前上下文
- [x] `@forge/sdk` 提供登录、Token 存储、请求自动携带 Token；tenant-app 有登录页并显示当前用户与租户
- [x] 集成测试覆盖登录成功、失败与下游验签


## Comments

2026-10-24 实现与验收：
- 登录采用标准 OAuth2.1 authorization_code + S256 PKCE；Spring Authorization Server 负责授权码、Token 签发与 refresh_token 轮换，服务端交换适配器持有 client secret，浏览器不持有客户端密钥。
- Identity 保持全局模型，通过 Membership 绑定 Tenant；预置账号由显式 `demo` profile 的 Flyway 脚本创建。启动步骤与接口见 [登录说明](../../../docs/tenant-login.md)。
- Redis 保存登录会话与标准授权状态；网关和安全 starter 分别通过 JWKS 校验签名、issuer、过期时间及上下文。刷新凭证并发消费最多一次成功。
- 后端先执行 `./mvnw clean verify`，审查修复后再次 `./mvnw verify`：最终 16 项测试通过；前端 10 项测试、typecheck、build、format:check 通过。
- Playwright 8 项通过，包含登录页与原有子应用回归。因本机 5175 已占用，在临时副本中将预览端口与对应 CORS 白名单映射到 15174–15176 执行，仓库配置保持原端口。
- 隔离 PostgreSQL/Redis + 打包 IAM、网关、demo-service 真链路通过：账号/邮箱登录、统一失败、下游验签、刷新轮换/重放拒绝、IAM 重启后的密钥和会话持续；已清理临时服务和容器。
- Access Token 即时吊销、多 Membership 选择与 Platform Realm 分别由后续工单处理。
- 规范与规格双轴审查均已复核通过；已补齐 URL 规范化、旧刷新响应隔离、Token 类型混淆与授权码互斥回归。

2026-10-26 审查修复：
- 新增 JSON `/auth/logout`，销毁 IAM 登录会话；Shell「重新登录」调用退出接口，失败时明确提示并可重试。退出不撤销已签发 Token，SSO 会话空闲超时、其他标签页与 Access Token 即时吊销边界已写明。
- 明确匿名交换适配器不认证浏览器客户端；已核对 Spring Security 7.0.8 的默认公开客户端 Refresh Token 策略。网关关闭 `/iam/oauth2/token`，内部地址支持监听地址、TLS、context-path 与显式配置。
- 保留已轮换 Refresh Token 摘要并检测重放，撤销同一授权的后续刷新能力；正常消费使用授权级互斥；锁争用期间的重放仍撤销授权，原子更新条件防止在途请求复活授权。
- SDK 明确排在 servlet 与 management 默认安全配置之前；`TenantContext` 与 demo-service 的 HTTP Response 分离。Shell 根组件提供唯一前端客户端供 LoginPanel、Sub-App 共享。
- 邮箱按大小写不敏感匹配并增加唯一性、基本格式约束；多 Membership 只在密码正确后返回明确提示。ADR-0001 记录 IAM 认证数据的 RLS 例外与适用边界。
- 后端 21 项测试、前端 12 项测试、8 项 Playwright 回归及类型、构建、格式检查通过。隔离三服务链路、数据库约束、HTTPS + context-path 下的交换/刷新/退出通过；临时服务、容器和私钥已清理。
- 审查补齐并发重放撤销与迟到初始化响应覆盖退出警告的回归；新增测试数据通过 `@Sql` 加载；未认证的直连 Token 请求不会触发撤销。
- 最终规范与规格双轴复核均无剩余问题；后端完整 `verify` 再次通过。

2026-10-26 IPv6 与前端类型后续修复：
- IAM 由 IPv6 监听地址推导内部 Token 基址时补齐方括号；在实际绑定 `::1` 且使用非默认 context-path 的环境中，修复前换码报 `Bad authority`，修复后换码、JWKS 验签及刷新通过。本机 JVM 的代理路由会影响 IPv6 回环验证，测试进程临时排除代理，未修改机器代理配置。
- `tenantClientKey` 改为 `InjectionKey<TenantClient>`，SDK 声明 Vue peer/dev 类型依赖；LoginPanel 保留运行时注入检查并移除非空断言。demo-service 的响应类型由 tenant-app 维护，SDK 不再导出。
- 补充无宽限期对复制标签页、独立客户端并发刷新的影响，以及 `:used-refresh` 历史摘要的容量估算和 TTL；工单 07 记录按授权聚合的后续评估，不改变当前重放与存储语义。
- 默认后端 21 项、前端 12 项与 8 项浏览器回归通过；另有 2 项 IPv6 定向回归通过，类型、构建和格式检查通过。
