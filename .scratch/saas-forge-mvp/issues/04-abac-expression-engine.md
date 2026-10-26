# 04: ABAC 表达式树引擎

**What to build:** SDK 中独立于 Spring 与数据库的 Data Policy 引擎（ADR-0006）：Data Policy 以 JSON 表达式树表示，引擎能把它编译成参数化 SQL 谓词，也能对单个对象做内存求值；同一策略、同一数据，两个执行器的结论必须一致。本工单是后续数据权限的前置地基，不接入任何业务服务。

**Blocked by:** 01 工程骨架与版本组合验证

**Status:** ready-for-agent

- [x] 支持逻辑节点 `and`、`or`、`not` 与比较节点 `{attr, op, value}`；`attr` 命名空间为 `subject.*`、`resource.*`、`env.*`
- [x] 支持操作符 `eq ne in nin gt gte lt lte contains is_null` 与部门「在子树下」（对应 PostgreSQL `ltree`）
- [x] `value` 支持字面量与主体属性引用（如引用当前主体的部门路径集合）
- [x] 资源属性到表列的映射由调用方提供，SQL 编译器只输出参数化谓词，不拼接用户值
- [x] 多个策略可合并为并集（OR）
- [x] Data Scope 预设（本人、本部门、本部门及下级、全部）以表达式树模板提供
- [x] 非法表达式（未知属性、未知操作符、类型不符）在加载时报错，而非运行时静默放行
- [x] 参数化测试：对全部操作符与预设，在真实 PostgreSQL（Testcontainers）上执行编译后的 SQL，与内存求值结果逐行一致
