# 03: 字典历史验收

**What to build:** 按 [spec](../spec.md) 完成端到端验收，覆盖真实修改后查看历史与 diff。

**Blocked by:** 01, 02

**Status:** ready-for-agent

- [ ] 修改字典项后，历史页签出现新版本
- [ ] 连续两次修改后，可比较任意两版并看到 added/removed/changed
- [ ] 无变化保存不产生版本
- [ ] 服务重启或 Manifest 升级不产生租户历史版本
- [ ] 前端组件测试 + 一条 Playwright 真实链路
- [ ] 审计事件仍独立记录，历史表不替代审计

## Comments
