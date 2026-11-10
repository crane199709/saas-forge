# 04: 平台端菜单管理页

**What to build:** 按 [spec](../spec.md) 第 7 节，复用工单 03 的编辑组件，在平台控制台新增 `/menu-layout` 页面。

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] 写页面简报并经用户确认，结构与租户端一致时可只说明差异
- [ ] 目录为 `consolePages` 的全部页面与菜单管理页，不按操作者本人权限裁剪；没有清单菜单
- [ ] 读取与保存 `/platform/navigation-layout`，「恢复默认」二次确认；保存后刷新当前 Operator 的权限与导航
- [ ] `consolePages` 增加菜单管理页，按 `platform:menu:manage` 显示
- [ ] 组件测试覆盖保存、恢复默认、读取或保存失败的提示，文案走 i18n
- [ ] 设计走查：新增视觉用例并逐张查看截图

## Comments
