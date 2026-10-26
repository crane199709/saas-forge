# 05: 前端 SDK 与应用迁移

**What to build:** `@forge/sdk` 解析统一信封：业务代码调用 `api<T>()` 直接拿到 `data`，失败时得到带 `code`、`userTip`、`traceId` 的 `ApiError`；分页调用 `page<T>()` 得到条目与分页信息。登录、切换、刷新按错误码判断。tenant-app、platform-console、demo-sub-app 改用新接口，错误提示展示 `userTip` 并附可复制的 traceId。

**Blocked by:** 03 安全与网关错误输出, 04 迁移 IAM 与 demo-service 接口

**Status:** ready-for-agent

- [x] `@forge/sdk` 导出 `ApiResponse`、`PageMeta`、`ApiError` 类型；新增 `api<T>(path, init)`，成功返回 `data`，失败抛 `ApiError`（`code`、`httpStatus`、`userTip`、`traceId`、`errors`）
- [x] 新增 `page<T>(path, init)`，返回 `{ items, page }`
- [x] `request()` 保留，继续返回原始 `Response`，现有的同源校验、401 合并刷新与重放、会话代次保护不变
- [x] `auth.ts` 的 login、selectTenant、memberships、switchTenant、refresh、logout 改为解析信封并按 `code` 判断；401 刷新与「刷新凭证被拒绝才清除会话、5xx 与网络故障保留会话」的行为不变
- [x] 响应体不是信封（如网络层或代理返回的 HTML）时得到明确的通用错误，不抛 JSON 解析异常
- [x] tenant-app 的 LoginPanel、TenantSwitcher、上下文加载及 demo-sub-app 改用 `api<T>()`；错误提示优先展示 `userTip`，旁附可复制的 traceId；`auth.*` 前端 i18n key 中被 `userTip` 取代的部分清理掉
- [x] 测试：更新 `auth-client.spec.ts`、`LoginPanel.spec.ts`、`TenantSwitcher.spec.ts`、`App.spec.ts` 中的响应模拟为信封；新增 `ApiError` 字段、非信封响应、分页解析的测试；test、typecheck、build、format:check 与 Playwright 回归通过

## Comments

2026-10-26 实现：`@forge/sdk` 新增 `src/api.ts`，导出 `ApiResponse<T>`、`PageMeta`、`FieldError`、`Page<T>` 类型与 `ApiError` 类（`code`、`httpStatus`、`errorMessage`、`userTip`、`traceId`、`errors`，`message` 等于 `code` 以兼容按字符串判断的旧代码）；`TenantClient` 新增 `api<T>()`、`page<T>()`，二者经 `request()` 发送，`request()` 本身未改。响应无法解析为信封、或分页信封缺 `meta.page` 时抛 `system.error`，`traceId` 取响应头 `X-Trace-Id`。`auth.ts` 的 login、exchange（selectTenant / refresh）、memberships、switchTenant、logout 改为解析信封，失败直接抛服务端 `ApiError`；refresh 仅在 `auth.failed`、`auth.tenantAccessDenied` 时清会话，switchTenant 仅在 `auth.failed` 时清会话，`system.unavailable`、非信封响应与网络故障均保留会话；logout 失败仍统一为 `auth.logoutFailed`。tenant-app 新增 `ErrorAlert.vue` 与 `error-tip.ts`：优先展示 `userTip` 并附 traceId 与复制按钮，SDK 本地 `auth.failed` 按 i18n 翻译，其余回退「请求失败，请稍后重试」；LoginPanel 上下文加载改用 `api<TenantContextResponse>()`。删除被 `userTip` 取代的 `auth.contextFailed`、`auth.tenantAccessDenied`、`auth.membershipsFailed`、`auth.switchFailed`，`auth.failed` 改为仅描述本地判定的「登录会话已失效」，新增 `error.*`。同步 [登录说明](../../../docs/tenant-login.md) 前端 SDK 描述。
- 偏离与约定：demo-sub-app 与 platform-console 目前没有任何接口调用，无需迁移。SDK 本地判定的错误（`auth.sessionChanged`、`auth.invalidPath`、`auth.invalidRedirect`、本地 `auth.failed`）仍是普通 `Error`。旧的 `auth.unavailable`、`auth.switchFailed` 由服务端 `system.unavailable` 等错误码取代。401 是否触发刷新仍只看 HTTP 状态码（`request()` 不读响应体）；刷新接口返回的非信封 401 不再清除会话。
- 验证：`auth-client.spec.ts` 29 项（新增登录 `auth.failed` 的 userTip/traceId、登录 503 不当作密码错误、刷新非信封 502 保留会话、切换 `auth.failed` 清会话、logout 200 信封、`ApiError` 字段、非信封响应、分页解析与缺分页信息）；`LoginPanel.spec.ts` 8 项（新增 userTip + traceId 复制、无 userTip 通用提示、选择租户被拒绝）；`TenantSwitcher.spec.ts` 3 项；`App.spec.ts` 改为信封。`pnpm test`、`pnpm typecheck`、`pnpm build`、`pnpm format:check` 通过；`pnpm test:e2e`（Playwright，路由 mock 改为信封）9 项通过。
