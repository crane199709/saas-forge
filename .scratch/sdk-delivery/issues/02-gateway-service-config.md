# 02: 网关按配置声明接入服务

**What to build:** 网关的服务前缀不再写死在代码里，改由配置声明已接入的服务（路径前缀、所属 Realm）。每个已声明前缀自动套用同一套规则：`/internal/**` 拒绝、`/v3/api-docs/**` 放行、其余要求认证且 Token 的 Realm 必须匹配；未声明前缀一律拒绝。OpenAPI 聚合分组由同一份配置生成。接入新服务不再需要修改网关代码。顺带修正 demo 与 platform 的 `/internal/` 当前未被拒绝的问题。见[规格 · 网关配置驱动](../spec.md#网关配置驱动)。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] 新增 `@ConfigurationProperties` record 绑定的服务列表，每项包含路径前缀与 Realm（tenant / platform）；不设默认值，列表为空或某项缺字段时启动失败
- [ ] 安全规则按配置生成：已声明前缀下 `/internal/**` denyAll，`/v3/api-docs/**` permitAll，其余 authenticated，并按 Realm 选择认证管理器；未声明前缀 denyAll
- [ ] iam 专属规则（匿名授权端点、JWKS、OIDC 发现、OAuth2 端点拒绝、`/iam/internal/**` 拒绝、`/iam/platform/**` 的 Platform Realm 要求）保留在代码中，行为不变
- [ ] 版本与租户状态校验（ADR-0005）、按租户限流对新声明的服务同样生效
- [ ] OpenAPI 聚合的分组由服务列表生成，不再单独维护
- [ ] Nacos 中的网关配置、主链路 E2E 的网关配置、本地开发配置补上服务列表（iam、platform、demo）
- [ ] 测试（沿用 `GatewaySecurityTest`）：前缀由配置声明而非写死；已声明前缀无 Token 401；Realm 不匹配被拒；`/<前缀>/internal/**` 带有效 Token 仍被拒；API 文档匿名可访问；未声明前缀被拒；服务列表漏配时上下文启动失败。现有网关测试全部通过
- [ ] 更新 [docs/agents/coding-standards.md](../../../docs/agents/coding-standards.md) 中网关相关条目，说明新服务通过配置声明
