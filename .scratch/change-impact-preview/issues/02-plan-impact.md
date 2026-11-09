# 02: Plan 降级预览

**What to build:** 按 spec 第 3 节，提供 Tenant 更换 Plan 前的预览接口。

**Blocked by:** —

**Status:** resolved

- [x] 显示失去的 Permission Code、Sub-App 和 `memberQuota` 差额
- [x] 显示受影响的 Membership 数量，并经 IAM 内部接口获取
- [x] 升级时失去项和受影响人数为空，已超出配额时仍显示差额；预览不改变 Plan
- [x] IAM 不可用时返回明确错误，不展示错误的零影响
- [x] 权限与修改 Plan 一致

## Comments

实现于 `69557a7`，审查修复于 `46faec8`。接口为 `GET /platform/tenants/{id}/plan/impact?planId=`。

用户决定（2026-11-09）：验收项原为「升级时结果为空」，与 spec 第 3 节「成员数与 `memberQuota` 的差额」冲突，改以 spec 为准。新 Plan 有成员配额时都向 IAM 查询成员数，已超额的租户升级也显示差额并弹窗；因此这类升级在 IAM 不可用时返回 503。只有新 Plan 不限成员且无损失时才不依赖 IAM。
