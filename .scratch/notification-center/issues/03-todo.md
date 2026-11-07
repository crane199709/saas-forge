# 03: Todo

**What to build:** 按 spec 第 4 节，由 `forge-iam` 维护 Todo 索引并提供查询接口。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 按（服务、业务键）幂等开启和关闭，关闭后所有处理人都看不到
- [ ] 测试覆盖关闭先于开启到达、迟到旧事件不生效、之后重新开启
- [ ] Permission Code 目标在读取时判断，权限收回后立即看不到
- [ ] 超出 Plan 的权限和 Sub-App 对应的 Todo 不显示
- [ ] demo-service 开启和关闭一条 Todo 完成端到端验证
- [ ] 接入租户清理流程

## Comments
