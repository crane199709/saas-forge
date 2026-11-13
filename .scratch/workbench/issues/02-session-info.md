# 02: 会话信息迁移与 demo 依赖移除

**What to build:** 按 [spec](../spec.md) 的“实现决策”，把首页的身份/租户/成员 ID 移到账号安全页的“当前会话”，并移除首页对 `/demo/context` 的调用。

**Blocked by:** 01

**Status:** resolved

- [x] 账号安全页新增“当前会话”卡片：身份名、租户名、Identity ID、Tenant ID、Membership ID
- [x] 首页不再显示任何 ID，也不再调用 demo-service
- [x] 租户上下文校验保留在主链路 E2E
- [x] 旧的首页组件与相关 i18n、测试 ID 清理
- [x] 组件测试覆盖当前会话字段与权限
- [x] 切换租户后当前会话与工作台同步刷新

## Comments

- 2026-11-13：票据 02 已领取；沿用已确认的组件外部行为接缝。页面简报：租户成员在账号安全页排障时查看当前会话；新增只读信息分区置于密码、验证器操作之后，沿用分组详情展示，无新增操作。本人会话无需读取权限；切换后同步 SDK 会话。视觉走查交由票据 03 统一完成。

## Answer

- 租户端账号安全页新增“当前会话”分区：身份名、租户名、Identity ID、Tenant ID、Membership ID。本人会话不依赖工作台读取权限；订阅现有 SDK 会话变化，切换后更新字段，返回工作台显示同一新租户的问候语。
- Identity ID 读取 SDK 当前会话 Access Token 的 `sub`；已核对 IAM `IamSecurityConfiguration` 的签发逻辑及 `TokenExchangeService` 的会话响应。只做展示，不参与鉴权；无法解析时显示 `—`，无需新增后端接口、SDK 字段或默认值。
- 原租户端 `HomePage` 组件及其测试更名为 `WorkbenchPage`，清理旧身份展示 i18n、无引用 `demo-context.ts` 与首页专用 `/demo/context` 测试桩。租户端异常页返回按钮改称“返回工作台”；运营端与共享 UI 未修改。固定导航 key `home` 保持兼容。
- 主链路已有双 Membership 数据范围、切换租户与 Tenant Realm 拒绝平台接口的真实校验均保留；`auth-client.spec.ts`、`MfaBindPage.spec.ts` 显式请求 `/demo/context` 的 SDK 覆盖保留。主链路旧首页断言和截图由票据 03 更新并执行验收。

验证（2026-11-13）：

- TDD：新增会话字段用例先因缺少“当前会话”分区失败，实现后通过；新增切换租户用例先因仍展示旧租户失败，接入会话监听后通过。
- 受影响组件与 SDK 回归：`pnpm --filter @forge/tenant-app test src/security/AccountSecurityPage.spec.ts src/WorkbenchPage.spec.ts src/LoginPage.spec.ts src/router.spec.ts src/security/SecuritySettingsPage.spec.ts src/App.spec.ts src/security/MfaBindPage.spec.ts src/auth-client.spec.ts`，8 文件、115 用例通过。
- `pnpm --filter @forge/tenant-app typecheck`、`pnpm lint:style`、本次修改文件的 `prettier --check` 通过。
- 本票据未运行真实平台 E2E 与视觉截图；由票据 03 在权限配套改动和本票据合并后统一执行，避免重复启动与基线冲突。
