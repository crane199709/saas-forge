# 工单 02：服务凭证控制台验收

2026-11-12，本票独立 worktree，分支 `codex/onboarding-02`。实现提交 `034bef4`，已合入包含工单 03 的集成分支 tip `c7e4b19`（合并提交 `3d52731`）。未操作共享环境。

## 实现结果

- IAM：`/internal/ops/service-credentials` 登记、列表（可按服务和 active/disabled 筛选）、停用、紧急原子轮换；统一信封和 `service-credential.*` 错误码。服务名限制与清单一致为 100 字符。登记允许未上报服务，返回 `manifestReported`。
- platform：`/service-credentials` 及其停用、轮换接口全部要求 `platform:service-credential:manage`，权限写入平台 Resource Manifest。浏览器传入的 actor 不参与命令；操作人仅来自验证后的 OperatorContext，经固定 `lb://forge-iam` 和独立 IAM Ops Token 提供给 IAM。该固定通道跳过已上报服务目录，现有 Ops 通道仍保留目录与路径校验。
- IAM 写生命周期审计，platform 不重复审计。新增明文响应 record 的 `toString()` 脱敏，列表不提供明文或摘要。
- `/service-credentials` 页面采用列表原型；登记对话框展示并复制一次性 `FORGE_MANIFEST_TOKEN=...`，关闭和刷新后清除。轮换入口预填服务名并指引登记、部署、停旧；停用需单独确认。IAM 不可用时页面显示不可用并禁用登记。
- 已同步接入清单、业务服务指南与 CHANGELOG。未新增编码规范或脚手架必配项。

## 验证

| 边界 | 结果与证据 |
| --- | --- |
| IAM HTTP/认证 | `./mvnw -o -pl forge-iam clean test -Dtest=ServiceCredentialManagementTest,ServiceTokenFilterTest,ArchitectureTest,ServiceCredentialArchitectureTest`：11 项通过。覆盖错误 Ops Token 401、服务名 400、非空可信 actor、未上报服务可登记、列表只含指纹、停用/轮换后旧凭证下一请求 401、active 列表排除旧凭证、认证查库无缓存和功能包架构。日志 `/private/tmp/onboarding02-iam-final.log`。 |
| platform HTTP/Ops | `./mvnw -o -pl forge-platform test -Dtest=ServiceCredentialOpsTest,OpsChannelTest`：7 项通过；管理权限缺失 403、平台以真实 Operator actor 覆盖浏览器伪造值、无清单服务登记、IAM 不可用为服务状态、既有 Ops 分页/业务拒绝兼容。另 `ArchitectureTest` 1 项通过。日志 `/private/tmp/onboarding02-platform-green.log`、`/private/tmp/onboarding02-platform-architecture.log`。 |
| 控制台模块 | `pnpm --filter @forge/platform-console test`：16 文件、92 项通过。覆盖登记、复制、关闭清除明文、停用和完整菜单目录；合入统一 `/api` 后执行。日志 `/private/tmp/onboarding02-console-final.log`。 |
| 类型/样式/格式 | 控制台 `typecheck`、`pnpm lint:style`、本票文件 Prettier 检查通过；Java Spotless 通过。 |
| 浏览器 fixture | Browser plugin not available，使用现有 Playwright/Chromium；`http://127.0.0.1:5183/service-credentials`，1440×900 与 1024×768；14 项通过，登记→复制→关闭→停用→状态变化→刷新明文不可取。校验标题、非空页面、无框架错误覆盖层及无应用控制台错误/警告。 |
| 视觉走查 | 列表、空列表、不可用、登记、一次性明文、停用确认、平滑轮换引导共 7 状态×2 视口，14 张 PNG 逐张查看；`pnpm test:visual service-credentials.spec.ts` 比对通过。日志 `/private/tmp/onboarding02-visual-compare.log`；扩展警告捕获后的最终复验 `/private/tmp/onboarding02-visual-final.log` 亦为 14/14。 |

红灯证据：初次 IAM 管理 HTTP 测试登记/校验返回 404（`/private/tmp/onboarding02-iam-red.log`）；首个 UI 行为测试在页面尚未实现时因入口缺失失败（`/private/tmp/onboarding02-ui-red.log`）。架构检查先发现跨功能控制器调用，修复后清理旧 class 并通过上述 11 项。

## 设计走查

基线在 `frontend/e2e/visual/__screenshots__/service-credentials-{list,empty,unavailable,register,issued,disable,rotate}-{desktop,laptop}.png`，均在 macOS 生成，与代码一同提交。

- 主任务与唯一主操作首屏可见；工具栏、表头、标签和行操作不折行。
- 空值显示 `—`；100 字符服务名省略展示且保留悬浮提示，不撑破布局。
- 1024 下服务名、状态、指纹与最后使用时间可读；创建时间可横向滚动查看，右侧操作固定且可达。
- 明文对话框自动折行，没有裁切；确认停用单独说明即时失效和部署影响。
- 未发现遮挡、溢出或框架错误覆盖层。视觉 fixture 只授予本页权限以隔离导航变化。

## 验收范围

本票的 IAM 使用真实 PostgreSQL/Redis 和 HTTP；platform 的 IAM 替身位于外部 HTTP 边界；浏览器使用 HTTP fixture。真实全栈登录和生命周期请求由根任务已编写的 `frontend/e2e/main-flow/service-onboarding.spec.ts` 在集成环境验证，本票不宣称已完成该真实场景。最终集成后端模块全量由根任务统一执行，避免多个 worktree 并行安装本地 Maven 制品互相覆盖。
