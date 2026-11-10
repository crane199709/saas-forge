# 05: 默认布局

**What to build:** 按 [spec](../spec.md) 第 9 节，把初始分组作为两端的默认布局写入数据库，没有自己的布局时返回默认布局。

**Blocked by:** 01, 02, 03, 04

**Status:** resolved

- [x] Flyway：新表 `navigation_default_layout`（标准表、`realm` 每个最多一条未删除记录的部分唯一索引、中文注释），同一迁移写入租户端与平台端两条初始布局
- [x] `navigation` 功能包按「自己的布局 → 默认布局 → null」计算生效中的布局，用于 `/auth/access`、`/platform/access` 与两端 `GET` 管理接口；保存与恢复默认不变
- [x] 初始分组为固定 id 的自定义分组，内容按第 9 节；两条默认布局能通过结构校验
- [x] iam 集成测试覆盖第 9 节验收；更新受影响的现有测试（原来期望 `layout` 为 null 的用例）
- [x] 视觉回归：两端导航按默认分组显示，逐张查看截图

## Comments
