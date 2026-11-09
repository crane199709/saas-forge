# 02: Plan 降级预览

**What to build:** 按 spec 第 3 节，提供 Tenant 更换 Plan 前的预览接口。

**Blocked by:** —

**Status:** resolved

- [x] 显示失去的 Permission Code、Sub-App 和 `memberQuota` 差额
- [x] 显示受影响的 Membership 数量，并经 IAM 内部接口获取
- [x] 升级时结果为空，预览不改变 Plan
- [x] IAM 不可用时返回明确错误，不展示错误的零影响
- [x] 权限与修改 Plan 一致

## Comments

实现于 `69557a7`，审查修复于 `46faec8`。接口为 `GET /platform/tenants/{id}/plan/impact?planId=`。

待决：租户已超出配额时换到无损失且配额未降低的 Plan，按本票「升级时结果为空」返回差额 0、不弹窗，与 spec 第 3 节「成员数与 `memberQuota` 的差额」字面不一致。当前保持本票口径。
