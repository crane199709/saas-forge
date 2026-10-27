# 租户数据采用共享 Schema + tenant_id + RLS 兜底隔离

每个服务拥有自己的 schema；在同一服务的 schema 内，所有租户共享同一组表，每张业务表带 `tenant_id`，由 SDK 自动注入租户过滤条件，并启用 PostgreSQL 行级安全（RLS）作为第二道防线，防止 SDK 漏注入时跨租户读写。由于 PostgreSQL 的 RLS 默认对表所有者与超级用户不生效，业务表必须同时启用 `FORCE ROW LEVEL SECURITY`，且执行 Flyway 迁移的账号（schema 所有者）与应用运行账号（仅数据读写）分离，两者都不得为超级用户或拥有 `BYPASSRLS`——否则 RLS 兜底会悄然失效。业务表另须带标准列并使用逻辑删除，见 ADR-0016。SDK 将「当前租户 → 数据源」抽象为扩展点，为将来大客户独立库预留空间，但第一期不实现。

## Considered Options

- 每租户独立 Schema / 独立库：隔离更强，但迁移、连接池与运维成本随租户数线性增长，第一期不需要。

## IAM 认证阶段的例外

当前最小登录阶段，`iam.identity` 是全局身份，`iam.tenant` 是租户目录，`iam.membership` 用于在建立 Active Tenant 之前的认证流程中发现该 Identity 的有效 Membership。这里无法先用一个可信的 `tenant_id` 设置普通业务 RLS；当前三张表未启用租户 RLS，这是限定在 IAM 认证数据上的显式例外，不是业务表的通用豁免。

应用仍使用非表所有者、无 `BYPASSRLS` 的 `iam_app` 账号。登录查询必须按凭证标识定位 Identity，后续授权按已认证 Identity 查询；刷新必须同时约束 Identity、原 Membership 和 Tenant，并检查三者状态。认证查询不接受客户端自称的租户上下文；选择或切换租户时客户端只能指定目标 Membership，查询仍以已认证 Identity 为条件，只在其自身三者均为 active 的 Membership 中查找。IAM 数据库与端口只供受信任的内部组件访问。

这个例外意味着 IAM 认证表缺少 RLS 的第二道隔离防线，依赖认证服务的查询边界。工单 06 的租户选择与切换只读取已认证 Identity 自己的 Membership，仍在此例外范围内；后续工单引入租户管理、成员关系读写前必须重新评估并设计认证发现与租户内访问的隔离；其他业务表仍须执行本 ADR 的 `ENABLE/FORCE ROW LEVEL SECURITY` 要求。
