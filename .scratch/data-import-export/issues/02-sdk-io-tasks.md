# 02: forge-sdk-io 任务基础设施

**What to build:** 按 [spec](../spec.md) 第 3.1、3.2、4、5 节，新 SDK 模块 `forge-sdk-io` 提供任务记录、本地异步执行与心跳、任务接口、中断收尾与结果清理；并调研选定 Excel 库。

**Blocked by:** 01, tenant-scheduling/03

**Status:** ready-for-agent

- [ ] 调研 Excel 库（Apache POI 流式 API 或其封装），要求读写流式；结论写入本工单 Answer
- [ ] 任务表（业务服务自己的 schema，SDK 提供迁移）：类型、状态、提交人 Membership、行数、失败原因、结果对象 ID、心跳时间；标准列与租户隔离
- [ ] 本地有界线程池执行，线程池满时拒绝提交并返回 `io.busy`；执行中定期更新心跳
- [ ] 异步执行时恢复提交时的租户、Membership 与授权上下文（对照 `forge-sdk-abac` 确认主体属性的传递方式并记录）
- [ ] SDK 自动注册任务接口：列出自己的任务、查询状态、获取结果下载票据；都校验提交人
- [ ] `@GlobalTask` 把心跳超时的执行中任务标记为失败（「执行中断，请重新提交」）
- [ ] `@TenantTask` 删除超过保留期（默认 7 天，可配置）的结果文件；任务记录保留
- [ ] 测试覆盖：他人任务不可见不可下载、线程池满、心跳超时被收尾、结果文件到期删除

## Comments
