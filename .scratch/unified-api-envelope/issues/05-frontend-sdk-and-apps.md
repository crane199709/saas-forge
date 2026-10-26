# 05: 前端 SDK 与应用迁移

**What to build:** `@forge/sdk` 解析统一信封：业务代码调用 `api<T>()` 直接拿到 `data`，失败时得到带 `code`、`userTip`、`traceId` 的 `ApiError`；分页调用 `page<T>()` 得到条目与分页信息。登录、切换、刷新按错误码判断。tenant-app、platform-console、demo-sub-app 改用新接口，错误提示展示 `userTip` 并附可复制的 traceId。

**Blocked by:** 03 安全与网关错误输出, 04 迁移 IAM 与 demo-service 接口

**Status:** ready-for-agent

- [ ] `@forge/sdk` 导出 `ApiResponse`、`PageMeta`、`ApiError` 类型；新增 `api<T>(path, init)`，成功返回 `data`，失败抛 `ApiError`（`code`、`httpStatus`、`userTip`、`traceId`、`errors`）
- [ ] 新增 `page<T>(path, init)`，返回 `{ items, page }`
- [ ] `request()` 保留，继续返回原始 `Response`，现有的同源校验、401 合并刷新与重放、会话代次保护不变
- [ ] `auth.ts` 的 login、selectTenant、memberships、switchTenant、refresh、logout 改为解析信封并按 `code` 判断；401 刷新与「刷新凭证被拒绝才清除会话、5xx 与网络故障保留会话」的行为不变
- [ ] 响应体不是信封（如网络层或代理返回的 HTML）时得到明确的通用错误，不抛 JSON 解析异常
- [ ] tenant-app 的 LoginPanel、TenantSwitcher、上下文加载及 demo-sub-app 改用 `api<T>()`；错误提示优先展示 `userTip`，旁附可复制的 traceId；`auth.*` 前端 i18n key 中被 `userTip` 取代的部分清理掉
- [ ] 测试：更新 `auth-client.spec.ts`、`LoginPanel.spec.ts`、`TenantSwitcher.spec.ts`、`App.spec.ts` 中的响应模拟为信封；新增 `ApiError` 字段、非信封响应、分页解析的测试；test、typecheck、build、format:check 与 Playwright 回归通过
