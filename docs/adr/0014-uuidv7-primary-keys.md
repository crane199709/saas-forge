# 主键使用应用侧生成的 UUIDv7

不使用 MyBatis-Plus 默认的雪花 long：雪花需要在多实例间分配 worker-id，且 long 传到前端存在 JS 精度丢失。UUIDv7 无需协调、按时间有序（索引友好），跨租户数据迁移合并也不冲突。数据库统一 PostgreSQL 17。
