# 01: 打通菜单图标链路

**What to build:** 让 Resource Manifest 里声明的 `icon` 真正显示在侧边菜单上。后端字段已存在并随 access 响应下发，前端在导航条目类型、菜单项类型、菜单转换、布局渲染四处把它丢掉了，本次补上这四层。同时给两个 Shell App 的内置页补图标，并把 `@forge/ui` 里两段内联 SVG 改为走同一个白名单。见[规格 4.1](../spec.md#41-图标链路)。

**Blocked by:** 无

**Status:** resolved

- [x] `@forge/ui` 新增图标解析导出：图标名（kebab-case 与 PascalCase 均可）归一化后查白名单（约 15–20 个），未命中返回默认图标；白名单是唯一事实来源
- [x] 侧栏图标标记为对辅助技术隐藏，不额外提供 aria-label（语义由菜单文字表达）
- [x] 导航条目类型与菜单项类型各增加可选 `icon`；菜单转换函数透传；`key`/`path`/`children` 语义不变
- [x] Shell App 的清单菜单映射透传 `MenuNode.icon`；`permission` 与 `actions` 仍由服务端裁剪，前端不重新计算
- [x] tenant-app 与 platform-console 的内置页注册表各补一遍图标（前者约 22 项、后者约 18 项）；demo Sub-App 不动
- [x] `@element-plus/icons-vue` 写进 `@forge/ui` 的依赖，版本与 `element-plus` 对齐；删除「本包不依赖图标库」的注释
- [x] 导航布局编辑器里两段内联 SVG 路径改为从白名单取，使图标来源只有一处
- [x] `sub-app.md` 增「图标」一节：白名单来源、名字写法、未命中回退行为；同步 `sub-app-kit` 模板与 `CHANGELOG.md`
- [x] 测试：组件测试覆盖图标名归一化、未命中回退、图标元素在文字之前；菜单转换测试覆盖 `icon` 透传；视觉测试覆盖侧栏图标与分组图标
- [x] `pnpm lint:style`、`pnpm -r test`、`pnpm test:visual:update` 通过，基线更新单独成一个提交

## Comments

**2026-11-14 合并与验收**

提交：`723014c0` 代码与文档、`e554f7de` 基线（214 张）、`2e8646fb` 合并集成分支、`c2e06266` 合并到集成分支。

验证：`--frozen-lockfile` 安装通过（确认手工补的 lockfile 一致）、`lint:style`、`typecheck`、`format:check`、串行全量测试通过；视觉 209 张基线在重跑后通过。

偏差：内置页实际为 tenant 12 项、platform 14 项，工单写的「约 22/18 项」把路由组件数当成了菜单项数（登录、选租户等页面不在菜单里）。

发现：`onboarding-mixed-laptop` 存在偶发抖动。首次全量后立即单跑会失败（12409 px，差异集中在文字栅格的亚像素偏移），随后连续 5 次全部通过。判断为冷启动相关的渲染抖动，**未修改该基线**。该项已转给工单 02 留意：任何基线重跑后必须连跑两次确认稳定。
