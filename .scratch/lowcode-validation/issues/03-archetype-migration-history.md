# 03: archetype 迁移脚本带着平台演进历史

**Status:** needs-triage

**现象：** 新生成的工程有 V1–V8 八个迁移，其中 `V3__orders_created_at.sql`、`V5__standard_columns.sql` 等是平台示例服务逐步演进留下的增量，对从零开始的新工程没有意义，且示例表（订单、分类）与 SDK 必需表（Outbox、消费去重、Dead Event 等）混在同一串版本里。业务方删除订单示例时难以分辨哪些迁移必须保留。

**期望：** 模板只带两份迁移：SDK 必需表（含 `COMMENT ON`）一份、示例业务表一份，后者可整体删除；`SchemaCommentTest` 的 SDK 表检查保持通过。
