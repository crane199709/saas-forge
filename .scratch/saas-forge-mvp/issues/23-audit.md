# 23: 审计

**What to build:** 系统自动记录三类 Audit Event：登录（成功、失败、退出、切换租户）、权限变更（角色、授权、Data Policy、Membership 变更）、业务操作（开发者在方法上声明即可，记录谁在哪个租户对什么做了什么、结果如何）。事件经消息异步写入按月分区的审计表。Tenant Admin 在 tenant-app 只能查看本租户的审计，超级管理员在 platform-console 查看 Platform Realm 的审计。

**Blocked by:** 08 Platform Realm 与运营登录, 09 Outbox 与租户感知消息, 16 租户角色管理

**Status:** ready-for-agent

实现说明见 [docs/audit.md](../../../docs/audit.md)。

- [x] SDK 提供 `@AuditLog` 声明式业务操作审计，记录成功与失败
- [x] 登录与权限变更事件由 iam 自动产出
- [x] 审计事件经 SDK 消息能力异步投递，写入失败不影响业务请求
- [x] 审计表按月分区，自动创建新分区
- [x] 查询支持按时间、操作人、动作、资源筛选并分页
- [x] 租户审计按租户隔离；平台审计与租户审计分开
- [x] 测试：demo-service 一次带 `@AuditLog` 的操作产生正确的审计记录；租户 A 的管理员查不到租户 B 的审计
