# 01: ObjectStorage 服务端读写

**What to build:** 按 [spec](../spec.md) 第 4 节，`ObjectStorage` 新增打开对象内容流与服务端写入对象两个入口。

**Blocked by:** —

**Status:** resolved

- [x] 打开对象内容流：只能读当前租户已完成上传的对象，其他租户对象与不存在一样返回 `OBJECT_NOT_FOUND`
- [x] 服务端写入对象：流式写入（不整体读入内存），登记到 `forge_storage_object`，校验存储 Quota，配额不足返回 `storage.quota_exceeded`
- [x] key 仍由 SDK 按当前租户与服务生成；写入失败时不留下计入容量的登记
- [x] public API 带文档注释
- [x] 测试覆盖：跨租户读取被拒、配额不足、写入后可经 `prepareDownload` 下载

## Comments

## Answer

`ObjectStorage.open` 与 `write` 提供服务端流式读写；读取校验已完成上传状态与当前租户前缀，写入在事务内校验配额、登记并确认，失败或外层回滚时移除存储内容且不留下计费登记。沿用项目既有 `storage.quotaExceeded` 错误码，符合编码规范的 lowerCamelCase。Excel 库选择与异步上下文说明见 [工单 02](02-sdk-io-tasks.md#answer)。
