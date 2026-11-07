# 01: SDK 与事件契约

**What to build:** 按 spec 第 2 节，新增 `forge-sdk-notification`，定义发送 Notification 和开启、关闭 Todo 的接口与事件。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] SDK public API 带文档注释，事务外调用明确报错
- [ ] 事件携带 Tenant、目标、内容、链接；Todo 事件携带业务键和发生时间
- [ ] 校验标题、正文长度和链接格式
- [ ] 新增 Topic 并更新 `deploy/rocketmq/create-topics.sh`
- [ ] SDK 不产生循环依赖，接入指南增加用法

## Comments
