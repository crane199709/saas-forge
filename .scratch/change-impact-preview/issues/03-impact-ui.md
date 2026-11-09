# 03: 确认弹窗

**What to build:** 在保存角色权限、数据策略和更换 Plan 前显示影响确认弹窗。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 无影响时不打断保存流程，有影响时显示确认弹窗
- [x] 预览失败时明确提示，由用户决定是否继续
- [x] 数据策略弹窗说明不判断具体数据行的变化
- [x] Playwright 覆盖确认和取消

## Comments

实现于 `ce71346`，审查修复于 `46faec8`。Playwright 使用真实前端构建，并用 `page.route` 替换 HTTP 边界，未连接真实后端和网关。
