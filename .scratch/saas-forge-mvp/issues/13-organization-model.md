# 13: 组织模型

**What to build:** Tenant Admin 在 tenant-app 维护本租户的 Department 树、Position 与 Extended Attribute 定义，并给 Membership 分配主部门、兼任部门、岗位与扩展属性值。这些数据可供后续数据权限作为主体属性使用。

**Blocked by:** 06 多租户成员与切换租户

**Status:** ready-for-agent

- [ ] Department 树以 PostgreSQL `ltree` 存储层级，支持新增、改名、移动（含子树）、删除（有成员或子部门时拒绝）
- [ ] Position 的增删改
- [ ] Extended Attribute 定义按租户维护（名称、类型、是否必填），值以 JSONB 存于 Membership
- [ ] 一个 Membership 有且仅有一个主部门，可兼任多个部门
- [ ] 组织数据严格按租户隔离
- [ ] SDK 能为当前 Membership 提供主体属性（所在部门路径集合、岗位、扩展属性）
- [ ] tenant-app 提供部门树、岗位、扩展属性与成员分配的管理界面
- [ ] 测试：移动部门后其子树路径正确更新；主体属性随分配变化
