# 04: 平台端菜单管理页

**What to build:** 按 [spec](../spec.md) 第 7 节，复用工单 03 的编辑组件，在平台控制台新增 `/menu-layout` 页面。

**Blocked by:** 03

**Status:** resolved

- [x] 写页面简报并经用户确认，结构与租户端一致时可只说明差异
- [x] 目录为 `consolePages` 的全部页面与菜单管理页，不按操作者本人权限裁剪；没有清单菜单
- [x] 读取与保存 `/platform/navigation-layout`，「恢复默认」二次确认；保存后刷新当前 Operator 的权限与导航
- [x] `consolePages` 增加菜单管理页，按 `platform:menu:manage` 显示
- [x] 组件测试覆盖保存、恢复默认、读取或保存失败的提示，文案走 i18n
- [x] 设计走查：新增视觉用例并逐张查看截图

## Comments

### 2026-11-10 页面简报（已经用户确认）

与租户端一致（见 [03](03-tenant-menu-layout-page.md) 的简报），差异：使用者是持 `platform:menu:manage` 的 Operator，确认弹窗说明全体 Operator 的导航恢复默认；目录只有 `consolePages`，没有清单分组。
