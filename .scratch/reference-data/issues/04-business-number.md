# 04: Business Number 与 Business Time Zone

**What to build:** 按 [spec](../spec.md) 2.2 与第 5 节，SDK 提供本地声明的 Numbering Rule 与事务内取号；`forge-sdk-core` 提供 `forge.business-zone`（定时任务共用）。

**Blocked by:** —

**Status:** resolved

- [x] `forge-sdk-core` 提供 Business Time Zone 配置 `forge.business-zone`（record 绑定，不设默认值）；由使用方模块在需要时强制要求，漏配即启动失败
- [x] Numbering Rule 声明：前缀、可选日期段（`yyyy`/`yyyyMM`/`yyyyMMdd`）、序号最小位数；计数周期跟随日期段
- [x] SDK 提供计数表（业务服务自己的 schema，按「租户 + 规则 + 周期」一行，标准列与租户隔离）与取号接口
- [x] 取号为加锁递增，须在调用方事务内；回滚时号一起回滚；超出位数继续增长
- [x] SDK 注册 `TenantDataCleaner` 逻辑删除计数行
- [x] demo-service 订单使用编号 `SO-yyyyMMdd-NNNN`
- [x] 测试覆盖：并发取号连续不重复、回滚无空号、按 Business Time Zone 跨周期重置、超出位数、漏配时区启动失败

## Comments
