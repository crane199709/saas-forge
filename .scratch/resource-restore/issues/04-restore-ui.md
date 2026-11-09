# 04: 租户端界面

**What to build:** 在 `tenant-app` 的角色、部门、岗位页面和 demo Sub-App 的订单页增加已删除视图和恢复操作。

**Blocked by:** 02, 03

**Status:** resolved

- [x] 按原管理权限显示入口
- [x] 部门以平铺列表显示已删除记录，被阻止时说明原因
- [x] 冲突错误提示明确，恢复后主列表刷新
- [x] Playwright 覆盖恢复和冲突

## Comments


## Answer

已完成角色、部门、岗位和 demo 订单的已删除视图、确认恢复及结果提示。

### 页面与接口契约

- IAM 的非分页数组通过 tenant-app 内部 `RestoreView` 接入；仅进入已删除视图时请求既有列表的 `?deleted=true`，恢复调用对应 `POST /{id}/restore`。删除数据保存在局部集合，RolePage、OrganizationPage 的有效角色、部门、岗位集合继续用于成员分配。
- IAM 沿用角色 `iam:role:manage`、组织 `iam:org:manage` 的菜单和路由入口。订单仅在 `order:delete` 下向 `ListPage` 提供受控 `deleted` 属性，普通 `order:read` 不显示切换或恢复入口；普通订单查询仍不传 deleted。
- 恢复前显示目标名称并确认，取消不提交。成功重新获取已删除列表并触发有效列表刷新；角色恢复后能看到原有授权，角色和岗位的同名记录均允许提交恢复，不增加名称唯一性限制。
- 删除时间显示 `updatedAt`，空值显示破折号；操作人显示 `updatedBy` 的身份 ID，空值显示“系统”。部门已删除记录直接平铺，不送入有效部门树；恢复受阻保留记录，展示服务端“请先恢复上级部门”及 traceId，先恢复父后可恢复子。
- 错误沿用 SDK ApiError 与 ErrorAlert，优先显示 userTip 并保留 traceId；角色编码与订单编号冲突不移除原行、不展示 errorMessage。恢复成功显示“恢复成功”。
- 订单切换视图重置第 1 页并保留 size=20、sort=createdAt,asc；恢复末页最后一条时重新加载并回退到最后一个有效页。已删除视图隐藏关闭订单、导入和导出操作。加载、关闭与恢复期间禁用切换和重复提交。

### TDD 与验证

按已确认的组件公开 DOM/props/events 和 Playwright 用户操作边界逐片 red → green：角色缺少已删除入口 → 确认后恢复并发出刷新事件；部门缺少入口 → 平铺、父级受阻及 userTip/traceId；岗位缺少入口 → 同名可恢复且取消不提交；订单浏览器测试在缺少已删除入口处失败 → 分页切换、末页恢复回退与主列表可见通过。

直接相关验证：

- `cd frontend/apps/tenant-app && pnpm exec vitest run src/role/RoleListPanel.spec.ts src/organization/DepartmentPanel.spec.ts src/organization/PositionPanel.spec.ts src/organization/org-api.spec.ts src/organization/OrganizationPage.spec.ts src/organization/MemberPanel.spec.ts src/role/RoleMemberPanel.spec.ts`：7 files、32 tests 全部通过；最终根节点简化后重跑三个受影响面板，27 tests 全部通过。
- `cd frontend && pnpm --filter @forge/tenant-app typecheck && pnpm --filter @forge/demo-sub-app typecheck && pnpm typecheck:tools`：通过。
- `pnpm build:spike` 与 `pnpm --filter @forge/platform-console build`：通过，生成仓库 Playwright 配置所需的真实 Shell/Sub-App 预览产物；最终源码的 `build:spike` 亦通过。
- `pnpm exec playwright test e2e/resource-restore.spec.ts --reporter=line`：6 tests 通过。覆盖四类恢复、角色同名与编码冲突、成员分配不混入已删除角色、部门平铺与先父后子、岗位同名、订单末页回退及编号冲突、无管理/删除权限隐藏入口与拒绝导航。
- 改动文件 Prettier 检查、`git diff --check`：通过。

工作树最初复用依赖链接时出现 Vitest 的 SDK 类身份不一致；改为 `pnpm install --offline --frozen-lockfile` 的独立依赖后上述 32 tests 全绿，依赖清单和锁文件没有变更，临时诊断已全部移除。

### 浏览器 QA 与证据

Browser plugin not available，使用仓库现有 Playwright/Chromium。公开 HTTP 边界由有状态路由夹具替代，Vue、Element Plus、路由、权限、共享 SDK 与联邦加载均真实运行；这项验证不等同于页面对真实后端的端到端联调。

入口为 `http://127.0.0.1:5174/roles`、`/org`、`/apps/demo/overview`；桌面 1280×900，窄屏 390×844。页面标题正确、内容非空、无 Vite overlay、无未解释的 console warning/error 或 pageerror；仅放行用例刻意模拟的 HTTP 409 资源错误。确认弹层关闭后再采集结果截图。

| 检查 | 结论 |
| --- | --- |
| 页面身份、非空、框架遮罩 | 通过 |
| 控制台健康 | 通过，预期 409 已限定匹配 |
| 四类资源交互及桌面呈现 | 通过 |
| 390px 窄屏视觉 | 存在现有 Shell 固定 220px 侧栏限制，表格内容区被压缩，不记为视觉通过 |

截图保存在工作树外，清理工作树后仍可查看：

- `/tmp/resource-restore-ui-roles.png`：角色恢复后有效列表与原授权。
- `/tmp/resource-restore-ui-role-conflict.png`：角色编码冲突、userTip 与 traceId，已删除行保留。
- `/tmp/resource-restore-ui-department-blocked.png`：部门平铺与上级已删除提示。
- `/tmp/resource-restore-ui-positions.png`：同名岗位均在有效列表。
- `/tmp/resource-restore-ui-orders.png`：末页恢复后订单重新出现在有效列表。
- `/tmp/resource-restore-ui-order-conflict.png`：订单编号冲突与 traceId。
- `/tmp/resource-restore-ui-mobile-active-baseline.png`：有效组织视图的既有窄屏侧栏挤压基线。
- `/tmp/resource-restore-ui-mobile.png`：窄屏已删除视图。断言 Shell 头部只有一个，resize 后等待两次 requestAnimationFrame 再截图，已消除未稳定重绘造成的底部重复头部影像；表格可读区域受限仍保留为已知限制。

交付前已合入集成最新提交 `0e67a4e`。全前端模块回归和双轴审查由集成代理统一执行。
