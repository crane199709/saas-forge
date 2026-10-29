# 19: 自定义条件与可视化编辑器

**What to build:** 除 Data Scope 预设外，Tenant Admin 可以在 tenant-app 的可视化条件编辑器中，用 Data Resource 的属性、主体属性（含 Extended Attribute）与环境属性组合任意 and/or 条件作为 Data Policy，保存后在 demo-service 上立即生效。

**Blocked by:** 18 数据权限端到端（Data Scope 预设）

**Status:** ready-for-agent

- [x] 编辑器可选的资源属性来自 Resource Manifest，主体属性来自组织模型与扩展属性定义
- [x] 支持嵌套 and/or、全部操作符，按属性类型限制可用操作符与取值控件
- [x] 保存前在服务端校验表达式合法性，非法表达式给出可理解的错误
- [x] 提供「预览」：对指定成员显示该条件在样例数据上的命中情况
- [x] 测试：通过编辑器保存的条件（如「金额大于 1000 且属于本部门及下级」）在 demo-service 列表中生效


## Comments

2026-10-29 实现：
- 存储：iam V14 给 `role_data_policy` 加 `condition jsonb`，`scope = 'custom'` 时必填、预设时为空，由 CHECK 约束。每个角色在每个资源上仍至多一条策略，预设与自定义条件二选一。`MembershipGrants` 中自定义条件原样发布，预设仍展开为模板。整体替换时按 JSON 树比较新旧条件：jsonb 会改写键序，未改动的条件不递增持有者的 version。
- 属性目录：forge-sdk-abac 的 `DataResourceDefinition` 新增环境属性 `env.now`（Unix 秒），并按租户的 Extended Attribute 类型（`string number boolean`）加入 `subject.attributes.<编码>`；`AttributeDefinition` 的名称允许多一级。`SubjectAttributes` 新增 `attributeTypes`（租户全部扩展属性定义的类型），随明细发布。SDK 的 `DataPermission` 按它加载条件，解析缓存键包含类型；上下文加入 `env.now`，同一请求内求值与编译用同一时刻。加载失败的条件（如扩展属性定义已删除）按拒绝处理并记录日志。
- 校验与接口：新增 `DataConditionService`。保存前按与 SDK 相同的属性目录解析条件，非法时 400 `role.invalidDataCondition`，提示中带出错节点的 JSON Pointer 与原因；空的条件组（空「全部满足」恒为真，会放开全部数据）一律拒绝；条件 JSON 上限 8192 字符。`GET /roles/data-resources` 带资源属性 `attributes`；新增 `GET /roles/condition-options`，返回平台主体属性、扩展属性、环境属性，以及部门（完整名称、ltree 路径）和岗位取值；新增 `POST /roles/data-policies/preview`，以指定成员当前的主体属性和此刻的环境属性，对至多 50 条样例逐条内存求值，返回 `matches` 与 `context`。样例不合法时返回 400 `role.invalidPreviewSample`。部门、岗位、扩展属性的读取经组织功能包 service 新增的免权限读取方法，由角色管理权限把关。
- 前端：`condition.ts` 是编辑器的条件树模型，负责与表达式树互转，并按属性类型限制操作符、引用属性与取值形态，规则与后端解析器一致。`ConditionGroup.vue` 递归编辑嵌套的「全部满足 / 任一满足」组，支持整组取反。部门、岗位、布尔值用下拉，数值用数字框，集合取值可输入多个；值可以是固定值，也可以引用类型相符的成员或环境属性。条件未填完或条件组为空时禁止保存。`ConditionPreview.vue` 选择成员、填写样例数据后展示命中与否，以及求值用的属性。角色列表中显示为「自定义条件」。
- 已知取舍：编辑器不提供 `not` 单条取反，只能整组取反，读回时 `{"not": 比较}` 会包成取反组。删除被条件引用的扩展属性不做阻止：成员明细重新发布后，该条件在业务服务中加载失败并按拒绝处理，iam 也会拒绝再次保存，需要人工修正。预览的成员下拉只取第一页 100 人。环境属性只提供 `env.now`，因为属性类型中还没有日期时间。
- 端到端覆盖方式：没有跨 tenant-app、iam、demo-service 三个进程串起来的单条测试。分三段覆盖同一条「金额大于 1000 且属于本部门及下级」：前端单测断言编辑器提交的 JSON；iam 测试保存该 JSON 并断言原样发布到 Redis，SDK 能按发布的类型加载；demo 测试把同样的 JSON 作为已发布明细写入 Redis，验证订单列表按条件过滤。
- 验证：forge-sdk-abac `DataResourceDefinitionTest` 新增 1 项（扩展属性按租户类型加载、环境属性、SQL 与内存求值、类型不符与未定义报错）；iam `RoleManagementTest` 新增 1 项（编辑器可选项、预览命中与样例错误、保存并发布原样条件、键序不同不算改动、7 类非法表达式（含空条件组）与缺条件、预设带条件被拒、改回预设），`OrganizationTest` 新增扩展属性类型发布的断言；demo `DataPermissionTest` 新增 1 项（「金额大于 1000 且属于本部门及下级」在订单列表中生效、引用扩展属性的条件、类型缺失时按拒绝、单对象校验与 SQL 过滤逐单一致）。前端新增 `condition.spec.ts` 5 项、`RoleListPanel.spec.ts` 3 项（读回并修改条件后提交、未填完不能保存、预览请求与结果展示）。
