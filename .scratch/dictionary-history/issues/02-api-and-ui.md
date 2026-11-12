# 02: 历史查询、diff 与页面

**What to build:** 按 [spec](../spec.md) 的“实现决策”，提供版本列表、单版本与两版 diff 接口，并在现有字典页面增加“变更历史”页签。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 列表接口返回版本号、变更摘要、变更人与时间，按版本倒序
- [ ] 单版本接口返回完整覆盖快照；当前值可对照
- [ ] diff 接口返回 added、removed、changed 三类
- [ ] 独立 Permission Code；只读，不提供回滚
- [ ] 字典页面增加页签：版本列表、版本详情、diff 对照
- [ ] 测试覆盖：列表、详情、diff、权限拒绝、跨租户隔离

## Comments
