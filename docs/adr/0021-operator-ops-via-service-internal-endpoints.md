# Operator 运维数据留在各服务，由平台经内部接口聚合

Dead Event 与 Task Run 分别存放在产生它们的服务库中。Forge SDK 在各服务暴露 `/internal/ops/*`，`forge-platform` 经 `lb://<service>` 调用并聚合，由平台校验 Operator 权限并记录审计。每个服务有独立的运维令牌，漏配时启动失败，服务列表取自 IAM 已上报 Manifest 的服务；某个服务不可达时，只把该服务标记为不可用。

选择这种方式，是因为补发和手动重跑必须回到原服务执行：无论查询走哪条路径，这条命令通道都少不了。在此基础上再增加一张中心表，只会多一份需要同步的状态。

## Considered Options

- 像 Audit Event 那样经 Outbox 上报到中心表：跨服务查询更快，但运维操作仍要回调服务，形成上报与命令两条链路；MQ 故障时也恰好看不到死信。
- 把 Operator Token 透传给 Business Service：每个服务只属于一个 Realm，这会让所有业务服务同时承担 Platform Realm 的鉴权。
- 服务间签发短期 JWT：令牌可自动过期，但需要新增签发和密钥管理；在现有 manifest 令牌已够用时，不值得增加这部分复杂度。

## Consequences

- 列表无法跨服务高效排序和分页，界面按服务分别查看。
- 每增加一个服务，都要为平台和该服务配置同一个运维令牌。
