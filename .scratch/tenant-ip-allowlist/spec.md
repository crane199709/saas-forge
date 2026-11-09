# 租户 IP 允许列表规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

Tenant 目前无法限制其 Membership 的访问来源。

目标：Tenant 可设置 Tenant IP Allowlist，在进入该 Tenant 和每次请求时校验。

非目标：Membership 级规则、Platform Realm 规则、Owner 豁免、按 Plan 控制。

## 2. 规则

- 登录是全局动作，不校验。同一 Identity 在租户 A 被限制时，不影响进入租户 B。
- 支持 IPv4、IPv6 和 CIDR，最多 100 条；拒绝 `/0`。空列表表示无限制。
- 保存时规则必须包含操作者当前的 IP，防止把自己锁在外面。
- 复用现有安全设置的权限；Operator 可紧急清除。
- 审计：`tenant.ipAllowlistUpdate`、`tenant.ipAllowlistClear`，以及签发 Tenant Token 被拒时的 `tenant.ipAllowlistDenied`。网关逐请求拒绝不写审计。

## 3. 校验

- **校验点：** 签发 Tenant Token（`/auth/exchange`、`/auth/switch`、`/auth/refresh`）和网关处理每个携带 Tenant Token 的请求。
- 客户端 IP 取自 `ClientIpFilter` 的结果。拒绝时返回 403，错误码 `tenant.ipNotAllowed`。
- 服务内部调用不经过网关，不受影响。

## 4. Redis 约定

- 规则发布到 `iam:{tenant_id}:` 下，由 `MembershipStateKeys` 定义键名；网关与现有状态在同一次 `MGET` 中读取。
- 未启用时写入明确的“无限制”值。**键缺失时拒绝访问**，与现有租户状态的失败策略一致。
- IAM 提供全量发布，并在启动时执行，供 Redis 数据丢失后恢复。
- **上线顺序：** 必须先让 IAM 完成全量发布，再启用网关校验，否则所有 Tenant 都会被拒绝。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。

## 实现与验收

工单 01–04 均已 resolved，全部实现及审查修复已合入 `codex/tenant-ip-allowlist-integration`。本地提交，未推送或部署。验证结果、审查修复和上线顺序见 [最终验收](acceptance.md)。
