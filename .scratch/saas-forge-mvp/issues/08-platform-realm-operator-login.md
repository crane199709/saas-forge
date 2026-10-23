# 08: Platform Realm 与运营登录

**What to build:** Operator 在 platform-console 用账号密码登录，iam 以独立于 Tenant Realm 的 issuer 签发 Token（ADR-0003）。网关按路由强制 issuer：平台路由只接受 Platform Realm Token，租户路由只接受 Tenant Realm Token。platform 服务经 SDK 接入并读取当前 Operator 上下文。

**Blocked by:** 03 Tenant Realm 最小登录链路

**Status:** ready-for-agent

- [ ] Operator 以 Flyway 迁移独立建表，不与 Identity 共用
- [ ] iam 同时作为两个 issuer 签发 Token，各自暴露 JWKS 与 OIDC 元数据
- [ ] 网关按路由配置可接受的 issuer
- [ ] platform 服务经 SDK 验签并读取当前 Operator
- [ ] platform-console 有登录页并显示当前 Operator；`@forge/sdk` 支持按应用配置 Realm
- [ ] MFA 是否强制的配置项存在（MFA 本身不实现）
- [ ] 测试：Tenant Realm Token 访问平台路由被拒；Platform Realm Token 访问租户路由被拒
