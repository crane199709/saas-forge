# 01: ObjectStorage 服务端读写

**What to build:** 按 [spec](../spec.md) 第 4 节，`ObjectStorage` 新增打开对象内容流与服务端写入对象两个入口。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 打开对象内容流：只能读当前租户已完成上传的对象，其他租户对象与不存在一样返回 `OBJECT_NOT_FOUND`
- [ ] 服务端写入对象：流式写入（不整体读入内存），登记到 `forge_storage_object`，校验存储 Quota，配额不足返回 `storage.quota_exceeded`
- [ ] key 仍由 SDK 按当前租户与服务生成；写入失败时不留下计入容量的登记
- [ ] public API 带文档注释
- [ ] 测试覆盖：跨租户读取被拒、配额不足、写入后可经 `prepareDownload` 下载

## Comments
