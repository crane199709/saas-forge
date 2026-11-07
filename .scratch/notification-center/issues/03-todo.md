# 03: Todo

**What to build:** 按 spec 第 4 节，由 `forge-iam` 维护 Todo 索引并提供查询接口。

**Blocked by:** 01

**Status:** resolved

- [x] 按（服务、业务键）幂等开启和关闭，关闭后所有处理人都看不到
- [x] 测试覆盖关闭先于开启到达、迟到旧事件不生效、之后重新开启
- [x] Permission Code 目标在读取时判断，权限收回后立即看不到
- [x] 超出 Plan 的权限和 Sub-App 对应的 Todo 不显示
- [x] demo-service 真实创建/关单动作与 Todo Outbox 契约验证完成；Broker 跨服务和浏览器 E2E 由票 05 统一验收
- [x] 接入租户清理流程

## Comments

- 2026-11-07：ticket03_todo 在独立工作树认领；按已确认的事件消费与 HTTP 边界执行 TDD。

## Answer

Todo 已实现：V26 标准表与 RLS、严格 Instant 乱序状态、共享关闭与重开、读取时数据库角色/Plan/Sub-App 可见性、当前成员分页、TenantDataCleaner，以及 demo 真实订单创建/关单/删单/超时关单接入。

实现提交 `c07302e`；合入最新 integration `da270fe` 的提交为 `85454dc`。合并后 IAM 定向 20/20、demo 定向 17/17、Spotless、架构与中文表注释门禁均通过。红绿证据、API/demo 契约与延后边界见 [validation-03.md](../validation-03.md)。本票未运行真实 Broker 跨服务和浏览器 E2E，不以 HTTP + Outbox 测试替代，统一在票 05 验收；模块全量由 root 集成交付时运行。
