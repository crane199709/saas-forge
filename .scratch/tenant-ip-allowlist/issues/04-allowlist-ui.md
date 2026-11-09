# 04: 界面

**What to build:** 在 `tenant-app` 安全设置页增加规则管理，在 platform-console 增加紧急清除，并为被拒绝的用户提供明确页面。

**Blocked by:** 02, 03

**Status:** resolved

- [x] 显示当前 IP，保存前提示生效范围，校验失败时定位到具体条目
- [x] 收到 `tenant.ipNotAllowed` 时显示专门页面，可切换到其他 Tenant 或退出，不反复刷新 Token
- [x] 平台端清除前需确认并填写原因
- [x] Playwright 覆盖保存、被拒和清除

## Comments


## Answer

已接入安全设置、专用拒绝页面和平台紧急清除，基于最新 integration `37e6c8d` 实现，交付前再次合并确认无新增变更。

- 安全设置沿用现有 MFA 卡片布局，显示服务端可信当前 IP；独立 GET/PUT `/iam/security-settings/ip-allowlist`，不会重置 MFA。逐行编辑 IPv4/IPv6/CIDR，最多 100 条；保存前明确提示影响当前租户全部成员进入及每次请求、空列表无限制、必须包含当前 IP。按 `errors[].field=rules[index]` 显示条目错误并在解除 busy 后聚焦首项；保存成功采用服务端规范化结果。
- SDK 增加带 TSDoc 的 IP 拒绝状态与晚订阅通知。普通 `api/page/request` 和 refresh 的 `tenant.ipNotAllowed` 标记当前 Tenant 被拒，保留 Refresh Token 并停止后续业务请求与自动刷新；exchange、switch 记录目标上下文，switch 目标被拒不会把仍可访问的当前 Tenant 标为被拒。成功新会话或退出解除状态；退出后迟到的 exchange 拒绝不能恢复旧状态。
- `POST /iam/auth/memberships` 用最新 Refresh Token 只读获取有效 Membership，不附 Bearer/Cookie，与 refresh/switch 共享 Web Lock，并在锁内读最新存储。专用 `/ip-not-allowed` 页面可进入其他 Tenant 或退出；目标 Tenant 被拒时还能返回原 Tenant。Shell 首次读取权限失败、整页刷新和晚安装守卫均进入此页；停止未读轮询，不误入普通 403 或刷新循环。
- 平台 Tenant 详情复用 `platform:tenant:manage` 控制紧急清除按钮，明确 Tenant 名称及解除全部来源限制的影响，原因 trim 后必填、最多 500 字；取消不发写请求，失败保留对话框与原因，确认才 POST `/iam/platform/tenants/{id}/ip-allowlist/clear`。关闭/切换详情使旧目标操作失效。只调整本次页面的窄屏可用性，未改版。

TDD 已在约定的公开 SDK + HTTP 与真实 UI 边界观察到 RED → GREEN：无 Bearer Membership 读取、普通/刷新/目标拒绝、单 Tenant 自动 exchange、锁内最新凭证、退出后迟到拒绝、安全设置逐条校验、专用页退出、平台必填原因。未修改后端，本工单不重复后端容器测试。

### 验证

- Tenant App 全量：31 个文件、187 个测试通过。
- Platform Console 全量：13 个文件、73 个测试通过。
- SDK、两个 Shell、工具 `typecheck` 全部通过；本次全部前端文件 Prettier、`git diff --check` 通过。
- Tenant、Platform、Demo 两版本构建通过，仅存在原有 chunk 大小提示。
- Playwright 工单流程 10/10 通过；补充成功保存后错误样式清除检查后，安全设置桌面与移动端 2/2 再次通过。覆盖保存/规范化/条目错误与焦点、access 首屏拒绝及 reload、api/refresh 拒绝退出、单/多 Tenant exchange、switch 目标拒绝返回原 Tenant、切 B、125 秒内无 refresh 循环/未读轮询、清除取消/空白原因/确认/只读权限。
- 首轮全量与浏览器同时以默认 worker 数运行，宿主负载导致多个原有测试超时；结束这些运行后，每模块 `--maxWorkers=2` 顺序复跑全部通过，未修改超时阈值。

浏览器环境：Browser plugin not available，使用仓库 Playwright / Chromium；Tenant `http://127.0.0.1:5174`、Platform `http://127.0.0.1:5173`，1280×900 与 390×844。只模拟公开 HTTP，SDK/路由/权限/真实组件交互均实际运行。页面 URL/title、非空内容、无框架错误 overlay、console/pageerror、交互和截图全部通过。主动模拟的 400/401/403 资源日志已在测试中明确区分；没有其他 app 错误。

已查看截图：`/private/tmp/tenant-ip-allowlist-settings-desktop.png`、`/private/tmp/tenant-ip-allowlist-settings-mobile.png`、`/private/tmp/tenant-ip-allowlist-denied-desktop.png`、`/private/tmp/tenant-ip-allowlist-denied-api-mobile.png`、`/private/tmp/tenant-ip-allowlist-clear-mobile.png`。截图/trace 未提交。浏览器验收发现并修复了禁用输入框无法聚焦与窄屏输入被侧栏挤压两项问题，复验通过。

```sh
cd frontend
pnpm --filter @forge/tenant-app test --maxWorkers=2
pnpm --filter @forge/platform-console test --maxWorkers=2
pnpm --filter @crane199709/sdk typecheck
pnpm --filter @forge/tenant-app typecheck
pnpm --filter @forge/platform-console typecheck
pnpm typecheck:tools
pnpm build:spike
pnpm --filter @forge/platform-console build
pnpm exec playwright test e2e/tenant-ip-allowlist.spec.ts --output=/private/tmp/tenant-ip-allowlist-playwright
pnpm exec playwright test e2e/tenant-ip-allowlist.spec.ts --grep '安全设置' --output=/private/tmp/tenant-ip-allowlist-playwright-settings
```

本工单 UI 验收使用 HTTP 模拟；真实后端登录/切换链路由父集成任务执行。未覆盖 WebKit/Firefox 和真实代理来源变化，后端的可信代理/IP 判定由工单 02 HTTP/网关集成测试验证。
