# 19: 自定义条件与可视化编辑器

**What to build:** 除 Data Scope 预设外，Tenant Admin 可以在 tenant-app 的可视化条件编辑器中，用 Data Resource 的属性、主体属性（含 Extended Attribute）与环境属性组合任意 and/or 条件作为 Data Policy，保存后在 demo-service 上立即生效。

**Blocked by:** 18 数据权限端到端（Data Scope 预设）

**Status:** ready-for-agent

- [ ] 编辑器可选的资源属性来自 Resource Manifest，主体属性来自组织模型与扩展属性定义
- [ ] 支持嵌套 and/or、全部操作符，按属性类型限制可用操作符与取值控件
- [ ] 保存前在服务端校验表达式合法性，非法表达式给出可理解的错误
- [ ] 提供「预览」：对指定成员显示该条件在样例数据上的命中情况
- [ ] 测试：通过编辑器保存的条件（如「金额大于 1000 且属于本部门及下级」）在 demo-service 列表中生效
