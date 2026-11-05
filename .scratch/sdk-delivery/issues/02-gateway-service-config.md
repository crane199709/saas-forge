# 02: 网关按配置声明接入服务

**What to build:** 网关的服务前缀不再写死在代码里，改由配置声明已接入的服务（路径前缀、所属 Realm）。每个已声明前缀自动套用同一套规则：`/internal/**` 拒绝、`/v3/api-docs/**` 放行、其余要求认证且 Token 的 Realm 必须匹配；未声明前缀一律拒绝。OpenAPI 聚合分组由同一份配置生成。接入新服务不再需要修改网关代码。顺带修正 demo 与 platform 的 `/internal/` 当前未被拒绝的问题。见[规格 · 网关配置驱动](../spec.md#网关配置驱动)。

**Blocked by:** 无

**Status:** resolved

- [x] 新增 `@ConfigurationProperties` record 绑定的服务列表，每项包含路径前缀与 Realm（tenant / platform）；不设默认值，列表为空或某项缺字段时启动失败
- [x] 安全规则按配置生成：已声明前缀下 `/internal/**` denyAll，`/v3/api-docs/**` permitAll，其余 authenticated，并按 Realm 选择认证管理器；未声明前缀 denyAll
- [x] iam 专属规则（匿名授权端点、JWKS、OIDC 发现、OAuth2 端点拒绝、`/iam/internal/**` 拒绝、`/iam/platform/**` 的 Platform Realm 要求）保留在代码中，行为不变
- [x] 版本与租户状态校验（ADR-0005）、按租户限流对新声明的服务同样生效
- [x] OpenAPI 聚合的分组由服务列表生成，不再单独维护
- [x] Nacos 中的网关配置、主链路 E2E 的网关配置、本地开发配置补上服务列表（iam、platform、demo）
- [x] 测试（沿用 `GatewaySecurityTest`）：前缀由配置声明而非写死；已声明前缀无 Token 401；Realm 不匹配被拒；`/<前缀>/internal/**` 带有效 Token 仍被拒；API 文档匿名可访问；未声明前缀被拒；服务列表漏配时上下文启动失败。现有网关测试全部通过
- [x] 更新 [docs/agents/coding-standards.md](../../../docs/agents/coding-standards.md) 中网关相关条目，说明新服务通过配置声明

## Comments

### 2026-10-30 实现与本地验收

- 通过 `forge.gateway.services` 声明 `prefix` 与 `realm`，安全规则与 Swagger UI 分组共用该列表。前缀限单个小写路径段；缺项、空列表、重复或保留前缀启动失败，IAM 默认 Realm 固定为 tenant。配置修改后重启网关生效。
- IAM 匿名授权、JWKS、OIDC、OAuth2 拒绝规则与 `/iam/platform/**` 的 Platform Realm 要求保留；业务路由仍由 Spring Cloud Gateway 配置维护。
- Nacos、主链路 E2E、本地配置均补齐 iam/platform/demo。配置新增服务沿用 Redis 版本与租户状态校验、租户限流，不再修改网关 Java 代码。
- TDD 验证旧实现对新增 `/orders` 返回 403，以及 Swagger 分组为空；修改后通过。保留前缀 `/webjars` 的非法配置测试也先复现失败后修复。
- `GatewaySecurityTest` 与 `TenantRateLimitTest` 27 项通过；网关完整测试 31 项通过，覆盖配置绑定失败、Tenant/Platform Realm 新服务、内部路径拒绝、文档匿名访问、未声明前缀拒绝、即时状态失效及限流。
- code-review 按机器资源串行执行 Standards / Spec 两轴审查，最终均无未解决问题。实现与本地验收完成，状态转为 `ready-for-human` 供人工审阅。
- 本次未发布 Nacos 配置、未部署服务、未运行真实主链路 E2E。
- 最终执行后端完整 `./mvnw verify` 成功：447 项测试，0 失败、0 错误、0 跳过，包含全部模块编译、Testcontainers 集成测试、ArchUnit 与 Spotless。三份 YAML 均解析通过。验证日志：`/tmp/gateway-full-verify.log`。
