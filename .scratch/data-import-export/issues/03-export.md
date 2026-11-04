# 03: Export Task

**What to build:** 按 [spec](../spec.md) 第 2、3.4、6 节，业务方用带注解的 `record` 描述导出模型，提交 Export Task，SDK 流式生成 xlsx，字典列输出名称。

**Blocked by:** 02, reference-data/03

**Status:** ready-for-agent

- [ ] 模型注解：列名、顺序、字典字段
- [ ] 业务方以查询条件提交，导出范围与列表接口一致（同一 Data Policy，按提交时身份计算）
- [ ] 流式写入 xlsx 到 `ObjectStorage`；字典列输出名称（含已停用项）
- [ ] 行数上限可配置（参考 10 万），超出时任务失败并提示缩小范围
- [ ] 任务结束时经 `forge-sdk-audit` 记录 `export.run`（行数、查询条件摘要）
- [ ] 测试覆盖：Data Policy 生效、字典名称翻译、超出上限、配额不足失败

## Comments
