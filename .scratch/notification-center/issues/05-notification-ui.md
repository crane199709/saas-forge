# 05: 铃铛、列表页与公告管理

**What to build:** 按 spec 第 6 节实现两端界面。

**Blocked by:** 02, 03, 04

**Status:** resolved

- [x] `tenant-app` 铃铛显示未读数，60 秒轮询，页面不可见时暂停
- [x] 列表页包含通知、公告和待办，可标记已读
- [x] 点击链接进入对应 Sub-App 路由，切换 Tenant 后内容随之切换
- [x] 两端公告管理页按权限显示
- [x] Playwright 覆盖收到通知、处理待办和发布公告

## Comments

- 2026-11-07：票05由 `codex/notification-ui` 认领，基线 `8cb9607`。测试边界沿用用户确认的 Playwright 与公开HTTP；Browser plugin not available，使用项目 Playwright。

## Answer

已实现租户铃铛和通知/公告/待办三列表、两端按独立权限控制的公告管理，以及 Demo 页面真实关单入口。隔离可选 MQ runner 复用 Topic 初始化与默认脚本，保持 main-flow/template 入口不变。

验收：前端各模块250项单测、全workspace类型/格式、受影响应用构建、23条相关Playwright回归和390px顶栏边界复核通过；真实MQ订单闭环17秒通过，真实两端公告21.8秒通过。已合入最终RLS集成tip，未push/PR/main。

详见 [validation-05.md](../validation-05.md) 和 [隔离验收入口](../../../deploy/e2e/notification.md)。日志/截图均在 `/private/tmp/notification-center-qa/`，不依赖工作树 ignored 目录。
