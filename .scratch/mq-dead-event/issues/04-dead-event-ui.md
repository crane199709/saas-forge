# 04: 平台控制台死信界面

**What to build:** 在 platform-console 增加按服务查看和处置 Dead Event 的页面。

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] 按服务切换，可按状态筛选，分页使用 `ListPage`
- [ ] 显示主题、Tag、租户、错误、尝试次数和时间，不显示 payload
- [ ] 重放和放弃都要求填写原因，失败时展示错误
- [ ] 服务不可用时给出提示，其他服务可继续查看
- [ ] 按 `platform:ops:read`、`platform:ops:manage` 显示入口和操作按钮
- [ ] Playwright 覆盖查看、重放和放弃

## Comments
