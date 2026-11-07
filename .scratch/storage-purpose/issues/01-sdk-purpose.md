# 01: SDK 用途声明与校验

**What to build:** 按 spec 第 2 节，在 `forge-sdk-storage` 增加 `StoragePurpose` 和校验，并修改 `ObjectStorage` 的接口。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 声明时校验编码格式和大小上限，非法声明启动失败
- [ ] 扩展名大小写不敏感，不在白名单或超限时返回对应错误码
- [ ] 对象表记录用途编码，下载时校验用途
- [ ] 下载 URL 时长取自用途
- [ ] SDK public API 带文档注释，测试覆盖各错误码和跨租户访问
- [ ] 更新 `schema.sql`，提供存量行回填方案

## Comments
