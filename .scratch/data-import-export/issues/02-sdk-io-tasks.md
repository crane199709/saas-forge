# 02: forge-sdk-io 任务基础设施

**What to build:** 按 [spec](../spec.md) 第 3.1、3.2、4、5 节，新 SDK 模块 `forge-sdk-io` 提供任务记录、本地异步执行与心跳、任务接口、中断收尾与结果清理；并调研选定 Excel 库。

**Blocked by:** 01, tenant-scheduling/03

**Status:** resolved

- [x] 调研 Excel 库（Apache POI 流式 API 或其封装），要求读写流式；结论写入本工单 Answer
- [x] 任务表（业务服务自己的 schema，SDK 提供迁移）：类型、状态、提交人 Membership、行数、失败原因、结果对象 ID、心跳时间；标准列与租户隔离
- [x] 本地有界线程池执行，线程池满时拒绝提交并返回 `io.busy`；执行中定期更新心跳
- [x] 异步执行时恢复提交时的租户、Membership 与授权上下文（对照 `forge-sdk-abac` 确认主体属性的传递方式并记录）
- [x] SDK 自动注册任务接口：列出自己的任务、查询状态、获取结果下载票据；都校验提交人
- [x] `@GlobalTask` 把心跳超时的执行中任务标记为失败（「执行中断，请重新提交」）
- [x] `@TenantTask` 删除超过保留期（默认 7 天，可配置）的结果文件；任务记录保留
- [x] 测试覆盖：他人任务不可见不可下载、线程池满、心跳超时被收尾、结果文件到期删除

## Comments

## Answer

Excel 采用 Apache POI 5.4.1：SXSSF 固定保留 100 行用于写入；按 XSSFReader + SAX 读取首个工作表，共享字符串内容与随机访问索引均落临时文件。导入校验后的 record 以 JSONL 落盘，校验全部通过后在单个事务中逐行消费，避免累计全部业务行。模板字典选项使用隐藏工作表与命名范围，规避内联下拉的长度限制。[Apache POI 官方流式 API 文档](https://poi.apache.org/components/spreadsheet/how-to.html)。

异步上下文：`CurrentTenant.wrap` 恢复租户，独立 `SecurityContext` 保存提交时已验证 JWT 的后台授权身份（标准列操作人记 NULL，发起人由 Audit Event 记录），`DataPermission.wrap` 捕获主体属性、Data Policy 与环境上下文，供 SQL 拦截与内存求值共用；任务结束恢复线程原状态。不会以缺少认证的系统身份执行。

Global Task 从 Redis `forge:io:<服务名>:tenants` 扫描提交过任务的租户 ID，再逐租户执行超时收尾，保持 RLS 生效，也覆盖已停用租户。该键需随服务保留。等待队列中的任务同样更新心跳；实例中断后等待与运行任务都标记失败，不重试。Tenant Task 按保留期分批删除结果，任务历史保留。
