# 02: Plan 降级预览

**What to build:** 按 spec 第 3 节，提供 Tenant 更换 Plan 前的预览接口。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 显示失去的 Permission Code、Sub-App 和 `memberQuota` 差额
- [ ] 显示受影响的 Membership 数量，并经 IAM 内部接口获取
- [ ] 升级时结果为空，预览不改变 Plan
- [ ] IAM 不可用时返回明确错误，不展示错误的零影响
- [ ] 权限与修改 Plan 一致

## Comments
