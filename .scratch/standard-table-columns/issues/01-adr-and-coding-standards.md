# 01: ADR 与编码规范

**What to build:** 把建表标准列、逻辑删除和 MyBatis-Plus 统一填充的决策写成可长期引用的文档，供后续工单和业务服务建表时遵守。只改文档，不改代码。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] 新增 `docs/adr/0016-standard-table-columns.md`，格式与现有 ADR 一致，记录 [spec](../spec.md) 第 11 节列出的决策；写明「逻辑删除进基线」的代价：唯一约束要改成部分唯一索引、手写 SQL 要自带 `NOT deleted`、数据不会被物理清除；同时写明放弃的方案：`deleted_at`、按表 opt-in、RLS 过滤已删除行、传递操作人
- [ ] `docs/agents/coding-standards.md` 的建表规范补充：标准表的范围与标准列定义（spec 第 2、3 节）；标准表禁止使用 `UNIQUE` 约束，只能用 `WHERE NOT deleted` 的部分唯一索引；实体继承 `AuditableEntity`；spec 第 9 节的手写 SQL 规则（第 4 条写为「删除使用能触发更新填充的方法」，具体方法由工单 02 验证后补充）
- [ ] AGENTS.md 的 Coding standards 摘要补一句标准列与逻辑删除规则
- [ ] ADR-0001 的建表要求旁边加一句交叉引用，指向 ADR-0016
- [ ] `GLOSSARY.md` 中 Cooling-off Period 等条目，以及 `.scratch/saas-forge-mvp/spec.md` 第 100 行附近关于冷静期后「真正清理」「清理本服务数据」的表述，改为「标记删除」，与 spec 第 6 节一致
- [ ] 文档内容与 [spec](../spec.md) 一致

## Comments
