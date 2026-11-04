# 05: 前端组件与 demo-service 订单导入导出

**What to build:** 按 [spec](../spec.md) 第 7–8 节，`@crane199709/ui` 提供导入对话框与导出按钮，demo-service 与 demo-sub-app 接入订单导入导出作为端到端验收。

**Blocked by:** 03, 04

**Status:** ready-for-agent

- [ ] 导入对话框：下载模板、上传（预签名直传）、进度、下载 Error Report、最近任务列表
- [ ] 导出按钮：提交、进度、完成后下载、最近任务列表
- [ ] 组件只调用所属服务的任务接口，不做 Shell 级任务中心
- [ ] demo-service：订单的下载模板、提交导入、提交导出接口，挂各自权限码；导入含字典列「客户来源」，导出带 Data Policy
- [ ] demo-sub-app 订单页接入两个组件
- [ ] `io.busy`、`storage.quota_exceeded` 等错误的 i18n 文案
- [ ] 组件测试与端到端验收覆盖 spec 第 8 节各项

## Comments
