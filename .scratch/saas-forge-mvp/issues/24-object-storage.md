# 24: 对象存储

**What to build:** 开发者通过 SDK 的 S3 抽象上传下载文件，对象 key 自动带 `{tenant_id}/{service}/` 前缀，访问一律经预签名 URL（ADR-0011）。demo 子应用为订单演示附件上传与下载；租户的存储容量受 Quota 约束。

**Blocked by:** 05 租户数据隔离, 20 套餐

**Status:** ready-for-agent

- [ ] SDK 存储 starter 基于 AWS SDK for Java v2，兼容任意 S3 协议存储
- [ ] 生成上传与下载预签名 URL，有效期可配置；key 前缀由 SDK 强制，业务代码无法越过
- [ ] 跨租户访问他人对象被拒
- [ ] 按租户统计已用存储，超出 Quota 时拒绝新上传
- [ ] demo-service 与 demo 子应用演示订单附件上传、列表、下载
- [ ] Testcontainers（S3 兼容存储）测试：上传下载成功；越权访问被拒；超配额被拒
