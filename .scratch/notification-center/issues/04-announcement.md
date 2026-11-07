# 04: Announcement

**What to build:** 按 spec 第 5 节，提供两端公告发布、撤回和读取接口。

**Blocked by:** —

**Status:** resolved

- [x] Tenant Admin 只能发布到本 Tenant，Operator 可发布到全部或指定 Tenant
- [x] 全部 Tenant 公告对生效期内新建的 Tenant 可见
- [x] 到期和撤回后不再显示，已读状态按 Membership 记录
- [x] 未读数包含当前可见的公告
- [x] 权限和审计符合 spec，两端权限互不通用
- [x] 接入租户清理流程

## Comments

- 2026-11-07：票 04 由 announcement 实现代理认领，分支 `codex/notification-announcement`。

## Answer

已实现 IAM 两 Realm 公告发布、管理历史和幂等撤回，以及租户读取、详情、Membership 已读和未读数。全租户规则在读取时生效，覆盖发布后创建的租户。V24 标准表、租户 RLS 与 TenantDataCleaner 已接入；两份权限清单各自升级并产出所属 Realm 的发布/撤回审计。

红绿切片与最终定向门禁通过，详见 [validation-04.md](../validation-04.md) 的 API/事件契约和验证命令。模块全量与 Playwright 由集成负责人和票 05 统一完成。
