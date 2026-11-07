# 01: SDK 与事件契约

**What to build:** 按 spec 第 2 节，新增 `forge-sdk-notification`，定义发送 Notification 和开启、关闭 Todo 的接口与事件。

**Blocked by:** —

**Status:** resolved

- [x] SDK public API 带文档注释，事务外调用明确报错
- [x] 事件携带 Tenant、目标、内容、链接；Todo 事件携带业务键和发生时间
- [x] 校验标题、正文长度和链接格式
- [x] 新增 Topic 并更新 `deploy/rocketmq/create-topics.sh`
- [x] SDK 不产生循环依赖，接入指南增加用法

## Comments

- 2026-11-07：ticket01_sdk 认领；验收边界为 SDK public API 的事务及事件契约，按红绿垂直切片实现。

## Answer

新增 `backend/forge-sdk/forge-sdk-notification`，提供 `NotificationClient.send/openTodo/closeTodo` 与 `NotificationSent`、`TodoOpened`、`TodoClosed` record。Tenant 来自 `CurrentTenant`，service 来自必填 `spring.application.name`；SDK 在业务事务内写既有 Outbox，共同提交/回滚。目标在 Membership 列表与 Permission Code 中二选一，内容和站内链接校验后才发布，Todo 保留业务键和调用方发生时间。

BOM、SDK reactor、`forge-notification` Topic 初始化及 `docs/business-service-guide.md` 已同步；SDK 无新增表，不需增加 SDK schema 或脚手架迁移。验收证据见 [validation-01.md](../validation-01.md)：19 个 SDK 公开边界用例及 1 个架构检查通过。IAM 消费、实际 RocketMQ 投递和 UI 流程由后续票验收。
