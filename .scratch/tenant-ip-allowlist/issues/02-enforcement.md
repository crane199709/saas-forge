# 02: 网关与 Token 签发校验

**What to build:** 按 spec 第 3、4 节，在签发 Tenant Token 和网关中执行校验。

**Blocked by:** 01

**Status:** resolved

- [x] 每个请求仍只多一次 Redis 往返，规则解析结果可按原始值缓存
- [x] 不在列表内返回 403 `tenant.ipNotAllowed`，且不触发前端刷新循环
- [x] 键缺失时拒绝，Redis 故障维持现有 503 行为
- [x] 同一 Identity 进入其他 Tenant 不受影响
- [x] Token 签发被拒时记录 `tenant.ipAllowlistDenied`
- [x] 测试覆盖可信代理、IPv6 和 CIDR 边界，并记录上线顺序

## Comments


## Answer

已在三种 Tenant Token 签发和网关逐请求认证中接入 IP 准入，未实现工单 03/04。

- `realmClaims` 在目标 Membership 已确认后校验其数据库规则；`TokenEndpointClient` 将外层请求的可信 `X-Forge-Client-Ip`（无头时连接 IP）透传到本进程 OAuth2 请求，避免来源退化为 loopback。exchange、switch、refresh 的拒绝都保持 403 `tenant.ipNotAllowed`，错误码与中英文文案统一由 SDK Core 提供。全局登录与 Platform Realm 不受限制。
- 签发拒绝写 `tenant.ipAllowlistDenied`，携真实 Identity、目标 Tenant、Membership 和失败码；无事务的拒绝路径保证异常不会回滚事件。被拒的刷新凭证不消费，仍可进入同一 Identity 的其他 Tenant。
- 网关同一次 MGET 读取 Membership 版本、Tenant 状态和规则；使用 `ClientIpFilter` 覆盖的来源 IP。规则缺失或非法时同样 403，Redis 读错误保持 503 `system.unavailable`；版本和租户状态继续沿用原有 401 语义。网关拒绝不产生审计，规则直接复用共享解析器，没有引入额外缓存或 Redis 往返。
- 新增全局恢复入口 `POST /auth/memberships`，JSON `{refreshToken}`，200 响应沿用 GET 的 `{membershipId, tenantId, tenantName}[]`。校验凭证精确匹配、Tenant 客户端、TENANT Realm、刷新 Token active、Identity active；不依赖当前 Access Token/Cookie，不签发、不轮换、不校验原 Tenant IP。读取只列此 Identity 的有效 Membership；已轮换 Token 的只读查询失败但不会撤销新授权，真正 refresh 的重放撤销逻辑保持不变。SDK 应无 Bearer 调用并与 refresh/switch 共用轮换锁，锁内取最新凭证。
- IAM 资源服务器仅将 GET memberships 纳入 Bearer 链；POST 是独立凭证入口，并按方法配置 CSRF。网关显式放行匿名 POST，GET 继续要求 Tenant Token。
- 每次成功签发还会安全补齐缺失的规则键；三个网关正常 fixture 与真实 E2E `run.sh` 的 seed Tenant 均发布明确 `*`。`deploy/images/README.md` 记录先 IAM 完成全量发布、后启用网关校验及 Redis 恢复顺序。

TDD 逐片验证观察到并修复了四项 RED：外部来源被限制时仍签发 200、拒绝审计缺失、恢复 POST 被旧 Bearer 链拦为 401、刷新后规则键仍为 null；对应 GREEN 均通过。后续边界用例覆盖可信代理链/伪造头、IPv4/IPv6/CIDR、缺键与非法规则、真实 Redis 拒读、跨 Tenant/Identity、错误 Token 类型、已轮换/撤销与 Platform 凭证。

验证命令与结果：

```sh
cd backend
./mvnw -o -pl forge-iam -am spotless:apply install -DskipTests
./mvnw -o -pl forge-gateway -am spotless:apply install -DskipTests
./mvnw -o -pl forge-iam spotless:apply test -Dtest=TenantIpEnforcementTest,TenantLoginTest,TenantMfaRequirementTest,PlatformLoginTest,ArchitectureTest -DforkCount=1
./mvnw -o -pl forge-gateway spotless:check test -Dtest=GatewaySecurityTest,GatewayLogContextTest,TenantRateLimitTest,ClientIpFilterTest,ArchitectureTest
./mvnw -o -pl forge-sdk/forge-sdk-core spotless:check test -Dtest=ErrorEnvelopesTest,ArchitectureTest
```

IAM 定向 45 个、网关定向 49 个、Core 定向 6 个测试通过；Spotless、`git diff --check` 与 `bash -n deploy/e2e/run.sh` 通过。IAM 首轮双 fork 回归遇到隔离 PostgreSQL 容器启动超时，单 fork 重跑全部通过。按整合约定未在工单内跑 IAM/网关模块全量；Playwright 与真实 E2E 由工单 04/整合阶段验收，本工单只校验 E2E 脚本语法。
