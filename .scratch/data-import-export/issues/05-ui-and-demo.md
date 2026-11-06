# 05: 前端组件与 demo-service 订单导入导出

**What to build:** 按 [spec](../spec.md) 第 7–8 节，`@crane199709/ui` 提供导入对话框与导出按钮，demo-service 与 demo-sub-app 接入订单导入导出作为端到端验收。

**Blocked by:** 03, 04

**Status:** resolved

- [x] 导入对话框：下载模板、上传（预签名直传）、进度、下载 Error Report、最近任务列表
- [x] 导出按钮：提交、进度、完成后下载、最近任务列表
- [x] 组件只调用所属服务的任务接口，不做 Shell 级任务中心
- [x] demo-service：订单的下载模板、提交导入、提交导出接口，挂各自权限码；导入含字典列「客户来源」，导出带 Data Policy
- [x] demo-sub-app 订单页接入两个组件
- [x] `io.busy`、`storage.quota_exceeded` 等错误的 i18n 文案
- [x] 组件测试与端到端验收覆盖 spec 第 8 节各项

## Comments

## Answer

UI 提供 ImportDialog、ExportButton，使用所属服务的任务回调；订单示例接入模板下载、直传、提交、轮询、结果下载与最近任务。清单 version 升至 7，新增 order:import/order:export，并验证只读成员被拒。组件测试验证上传票据不携带租户认证、错误报告与自动导出下载；Playwright 以模拟接口验证实际 Shell + Sub-App 在桌面/手机上的渲染与交互，后端真实 PostgreSQL/Redis/S3 的验收在 ObjectStorageTest 完成。
