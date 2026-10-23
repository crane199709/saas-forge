# 对象存储只面向 S3 协议，不绑定 MinIO

MinIO 社区版服务端仓库已于 2026-04 归档，仅提供 AGPLv3 源码、不再发布二进制。SDK 只提供 S3 抽象，客户端用 AWS SDK for Java v2；生产使用云厂商的 S3 兼容存储，本地开发可用任意 S3 兼容实现。所有租户共用 bucket，对象 key 以 `{tenant_id}/{service}/` 为前缀，访问一律经预签名 URL。
