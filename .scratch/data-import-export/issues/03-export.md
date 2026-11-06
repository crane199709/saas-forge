# 03: Export Task

**What to build:** 按 [spec](../spec.md) 第 2、3.4、6 节，业务方用带注解的 `record` 描述导出模型，提交 Export Task，SDK 流式生成 xlsx，字典列输出名称。

**Blocked by:** 02, reference-data/03

**Status:** resolved

- [x] 模型注解：列名、顺序、字典字段
- [x] 业务方以查询条件提交，导出范围与列表接口一致（同一 Data Policy，按提交时身份计算）
- [x] 流式写入 xlsx 到 `ObjectStorage`；字典列输出名称（含已停用项）
- [x] 行数上限可配置（参考 10 万），超出时任务失败并提示缩小范围
- [x] 任务结束时经 `forge-sdk-audit` 记录 `export.run`（行数、查询条件摘要）
- [x] 测试覆盖：Data Policy 生效、字典名称翻译、超出上限、配额不足失败

## Comments

## Answer

`ExcelIo.export` 接收公开 record 与逐行数据源，按列注解输出 xlsx，字典使用全部项名称（含停用项），超限提示缩小范围。订单导出复用列表排序与 Data Policy；回归验证本人范围、提交时权限快照、字典名称、行数上限、配额不足与任务归属。结束记录 export.run 的行数与查询摘要。
