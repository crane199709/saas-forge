# Tenant 组织不参与授权，管理员默认获得业务 Permission

2026-10-14 第 4 阶段设计访谈确认：每个 Tenant 一个 Organization，其中允许多个根 OrganizationUnit，形成多棵树。Organization 只表达 Tenant 内的人员结构，组织归属不隐式授予权限或限定业务数据可见范围；一个 Membership 可关联零到多个 OrganizationUnit，不设置主部门，也不重复计入 `max_users`。仅允许删除没有子节点且没有成员关联的组织单元；其他单元须先显式移动子节点、调整成员归属，不提供递归删除或隐式迁移。MVP 选择组织管理与操作授权分离，避免引入尚未定义的数据访问范围和组织角色继承。

Permission 由平台及业务应用受控定义和注册；业务目录通过受控发布清单导入，见 [ADR 0058](0058-business-permissions-use-controlled-release-catalogs.md)。Tenant 管理员可查看目录、创建 Tenant Role 和分配已有 Permission，不能任意创建或修改权限代码。Permission 统一使用 `namespace:resource:action` 三段具体代码，命名空间由受控注册确定；第 4 阶段官方 Example 使用 `example:project:create`、`example:project:list`、`example:task:list` 等与实际 CRUD 对应的代码。这样权限目录中的能力须有对应的服务端执行语义，而非只有菜单或配置项。

Tenant Administrator Role 自动获得所有受控注册且有效的租户业务 Permission，包括后续新增项，无须逐 Tenant 再次授权；Platform Permission 不在此范围。这扩展了此前仅绑定核心 `system:*` Permission 的约束，选择管理员持续具备业务操作能力，而非新增 Permission 后逐一授权。授权仍通过该既有系统角色表达，不新增超级用户标记，也不绕过 Tenant 访问、Feature 或 Quota 判定；本决策不预先选择通配符或具体绑定更新机制。

一个 Membership 所持多个 Tenant Role 的 Permission 取并集，未获得即拒绝；MVP 不引入显式 deny、角色继承或直接授予 Membership Permission。

组织维护、邀请、普通成员启用/禁用可通过自定义 Tenant Role 委派。角色配置、Role-Permission 与 Membership-Role 分配，包括授予或撤销 Tenant Administrator Role，只属于现有 Tenant Administrator；对应管理 Permission 为系统保留，自定义 Tenant Role 不得获得。普通管理者不能禁用管理员 Membership，也不能通过邀请授予管理员角色。

成员禁用或角色撤销不得使 Tenant 失去最后一个启用且持有 Tenant Administrator Role 的 Membership，并须在并发变更下维持此约束；Tenant 冻结、到期和关闭仍遵循各自独立规则。Initial Tenant Administrator 继续是不可变历史关系，不构成绕过现有角色分配的管理权限。

以上为已确认的设计边界，不表示组织、通用 RBAC 或上述保护已实现。
