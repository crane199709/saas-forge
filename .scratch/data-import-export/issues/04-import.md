# 04: Import Task 与 Import Template

**What to build:** 按 [spec](../spec.md) 第 2、3.3、3.5、6 节，SDK 从导入模型生成 Import Template，执行「全部校验通过才写入」的 Import Task，失败时生成 Error Report。

**Blocked by:** 02, reference-data/03

**Status:** ready-for-agent

- [ ] Import Template：表头、列宽、必填标记；字典列加 Excel 下拉校验，选项为当前租户可选项名称
- [ ] 以已上传对象 ID 提交；流式解析并校验全部行：字段校验、字典名称匹配为编码（不接受编码）、业务方校验
- [ ] 任一行出错：一行不写，生成 Error Report（原行加错误原因列）
- [ ] 全部通过：在一个事务中调用业务方写入
- [ ] 行数上限可配置（参考 1 万），超出即失败
- [ ] 任务结束后删除源文件
- [ ] 任务结束时记录审计 `import.run`（行数、结果）
- [ ] 测试覆盖：模板下拉、名称匹配与未知名称、有错行时零写入、全部通过时全写入、源文件被删除

## Comments
