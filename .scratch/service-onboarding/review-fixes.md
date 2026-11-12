# 接入改造评审修复记录

日期：2026-11-12。基于集成分支 `codex/service-onboarding` 的 `daa4d38`；提交前重新合入该分支，结果为 already up to date。工单状态未调整。

## 修复范围

- Standards P2：IAM 凭证库访问/事务失败时保留完整服务端异常与当前请求 MDC trace，响应继续使用安全的 503 `service-credential.unavailable` / `Internal error`。日志消息不拼接令牌、请求头或摘要。
- Standards P3：IAM/Platform 登记请求改为 `@Valid` record，service 声明 `@NotBlank`、100 字符上限及 `[a-z][a-z0-9-]*`；IAM 三个写端点的可信 actor 声明 `@NotNull`。移除 Controller 手工 actor 校验，沿用 SDK 的 400 `request.invalid` / 字段 `errors`。IAM 功能服务的领域校验、Platform `OperatorContext` 转交及独立 Ops Token 链保持原行为。
- 集成 harness：Playwright onboarding 配置改为单个顶层对象 spread，只设置一次 `webServer`，保留 main-flow 的 channel、trace、screenshot 等配置；真实配置只有 15174 / 15175 / 15173 三个服务，均不复用现有服务器。
- 集成 harness：内部路径拒绝断言读取真实登录后的 `forge.platform.session.accessToken`，携带 Platform JWT 请求网关 `/platform/internal/onboarding-status`，严格期待 403。

## Red → Green

均沿用已批准 HTTP seams，数据库故障只在 DataSource 外部边界注入；未 mock 内部功能服务。

| 切片 | Red 证据 | Green 证据 |
| --- | --- | --- |
| 503 根因日志与秘密保护 | `/private/tmp/onboarding-review-p2-red.log`：1 项失败，响应 trace 下的事件没有 Throwable | `/private/tmp/onboarding-review-p2-green.log`：1/1 通过；响应 trace 与日志 MDC 一致，完整 SQLException 根因与堆栈可见，明文/Authorization/Bearer/完整 SHA-256 不出现 |
| IAM 登记及 actor 字段错误 | `/private/tmp/onboarding-review-p3-iam-red.log`：2 项失败，原响应是领域错误且无统一字段契约 | `/private/tmp/onboarding-review-p3-iam-green.log`：4/4 通过；覆盖 service 缺失、空串、空白、格式、超长，以及 create/disable/rotate 缺失 actor |
| Platform 登记字段错误 | `/private/tmp/onboarding-review-p3-platform-red.log`：1 项失败，缺失 service 仍调用下游并返回 201 | `/private/tmp/onboarding-review-p3-platform-green.log`：4/4 通过；字段错误在 HTTP 边界返回，已有权限与可信 actor 测试继续通过 |

## 交付验证

- 后端命令：`cd backend && ./mvnw -o -pl forge-iam,forge-platform test -Dtest=ServiceTokenFilterTest,ServiceCredentialManagementTest,EmptyServiceCredentialStoreTest,ServiceCredentialArchitectureTest,ServiceCredentialOpsTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`。IAM 13/13、Platform 5/5 通过，含双模块 Architecture 与认证路径禁止缓存守护。日志：`/private/tmp/onboarding-review-targeted.log`。
- 日志捕获改为事件快照后，再运行完整 `ServiceTokenFilterTest`，6/6 通过；证据：`/private/tmp/onboarding-review-p2-final.log`。
- `./mvnw -o -pl forge-iam,forge-platform spotless:apply spotless:check` 通过，证据：`/private/tmp/onboarding-review-spotless-final.log`。
- `cd frontend && pnpm exec playwright test -c playwright.onboarding.config.ts --list` 列出 3 项真实 E2E；`pnpm typecheck:tools` 与两个修改文件的 Prettier 检查通过。
- 通过 TypeScript transpileModule 加载真实配置并检查：webServer 仅有 15174、15175、15173；全部 `reuseExistingServer=false`；保留 `channel=chromium`、`trace=retain-on-failure`、`screenshot=only-on-failure`，onboarding baseURL 为 15173。

本修复未运行全栈 `deploy/e2e/run.sh`，未启动或复用用户服务器，未改界面及截图。依赖顺序的模块全量、真实全栈 E2E 与最终工单关闭由集成负责人完成；本记录不将其计为通过。
