# Service onboarding 实施与验收

## 实施范围

- 用户于 2026-11-12 调用 `implement-spec`，授权实现本目录规格与四个工单。
- 集成分支：`codex/service-onboarding`；基线：`1e315c5bbb8c92c8ad47eb64e5dab555b21757f2`。
- 依赖图：01、03 无依赖；02、04 依赖 01。
- 工单独立分支与 worktree，合并后进行规格、规范双轴审查。
- 只使用本地隔离测试资源；不迁移共享开发环境，不修改已有开发容器数据。

## 验收边界

依据规格与工单已明确的接口和行为验收：

1. IAM internal HTTP：两条凭证、请求级停用、静态引导回退、数据库故障 503、身份绑定、审计脱敏与禁缓存架构约束。
2. IAM 管理 API 与 platform Operator API：服务名校验、权限 403、一次性明文、停用、原子轮换和不可用状态。
3. SDK `api()` / `page()`、固定 Vite 与 Nginx 入口：业务请求 `/api`，认证 `/iam`，同源约束、SPA/静态资源/HMR 段边界不变。
4. SDK 上报与只读诊断 HTTP：分类、有限重试与停止、IAM 不可用仍启动、内部端点保护、三级证据不自证。
5. 浏览器：凭证登记与停用、诊断状态、真实 Shell 子应用业务请求；页面桌面与笔记本截图走查。

## 实施解释

- 平滑轮换遵循 ADR-0023：创建新凭证 → 部署 → 明确停用旧凭证。工单 02 要求的原子 `rotate` API 同时保留，立即停旧的后果必须明确。
- `platform-console` 同样使用 SDK，须同步固定代理及受影响请求夹具，避免统一入口后返回 HTML。
- 真实 HTTP 诊断必须记录实际请求结果及证明范围；配置存在或服务自报不能推导第三级成功。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| IAM 独立全量（先验收 01） | 314 项通过，0 失败/错误/跳过；`4084258` 先行关闭 01 |
| Platform 全量 | 61 项通过，0 失败/错误/跳过 |
| Manifest / Ops / OpenAPI SDK 全量 | 50 / 5 / 2 项通过，0 失败/错误/跳过；受影响后端合计 432 项 |
| 合并后控制台全量 | 17 个文件、93 项通过 |
| 入口工单前端覆盖 | UI 67、tenant-app 229、kit 7 项通过；控制台最终以上述 93 项为准 |
| 既有浏览器回归 | 65 项覆盖通过，修复旧 mock 前缀后重跑受影响文件通过 |
| 合并后全量视觉 | 150 项通过，无需重生成旧菜单基线；新增页面共 20 张 1440/1024 基线已逐张查看 |
| 既有真实 Java 主链路 | 3 项通过：双 Membership 与切换、Realm 隔离、停用后立即登出 |
| 新增真实隔离接入流程 | 3 项通过：凭证登记/明文不再查询/立即停用/用户 JWT 无法读内部端点；Shell 与远程 Demo JSON 请求及整页刷新；诊断运行状态与配置未知，以及实际网关 OpenAPI 服务身份 |
| 构建与静态检查 | 受影响依赖 clean install、Spotless、全前端 typecheck、lint:style、format:check、git diff --check 通过 |

执行顺序：01 的实现与全量验收闭环后，才最终关闭依赖工单 02、04；先前误标的状态已纠正，全部四票及本规格现为 resolved。

关键命令：

```sh
# backend/
./mvnw -o -pl forge-iam,forge-platform,forge-sdk/forge-sdk-manifest,forge-sdk/forge-sdk-ops,forge-sdk/forge-sdk-openapi -am clean install -DskipTests
./mvnw -o -pl forge-iam test
./mvnw -o -pl forge-sdk/forge-sdk-manifest,forge-sdk/forge-sdk-ops,forge-sdk/forge-sdk-openapi,forge-platform test
# frontend/
pnpm typecheck
pnpm lint:style
pnpm format:check
pnpm test:visual
# repository root
FORGE_ONBOARDING_E2E=1 deploy/e2e/run.sh
```

后端全量日志：`/private/tmp/service-onboarding-iam-full.log`、`/private/tmp/service-onboarding-dependent-full.log`。视觉日志：`/private/tmp/service-onboarding-visual.log`。最终真实接入日志：`/private/tmp/service-onboarding-e2e-clean.log`；独立容器已随脚本正常清理。

## Standards

审查发现 2 项：P2 数据库 503 丢失异常根因、P3 管理请求缺少 Bean Validation。两项均在 `240eeee` 修复，HTTP 回归验证完整异常与 traceId 关联、凭证/摘要不泄漏，以及 `request.invalid` 字段 errors；无未解决规范项，无需处理的 Fowler smell。

## Spec

独立审查发现 0 项规格偏差。完整结论及修复证据见 [review-fixes.md](review-fixes.md)。

## 浏览器 QA

Browser plugin not available，采用已有 Playwright Chromium。真实流程为密码登录 → 控制台登记 → IAM 认证成功 → 刷新后明文消失 → 停用 → 下一请求 401；另经真实 Shell 加载远程 Demo、请求 JSON、整页刷新，再登录诊断页读取服务状态。真实测试不 mock 网络、不注入会话。

环境：平台 `http://127.0.0.1:15173`，租户 Shell `15174`，Demo remote `15175`，隔离 Java 网关 `18000`；1440×1000。页面基线另覆盖 1440×900、1024×768。生产 Nginx 与临时 Lowcode 新 SDK 产物的 HTTP/浏览器边界见 [工单 03 验收](acceptance-03-api-entry.md)。

| 检查 | 结论 |
| --- | --- |
| URL / 页面标题 | 通过 |
| 页面有实际内容 | 通过 |
| 无框架错误覆盖层 | 通过 |
| 页面 console error/warning 与 pageerror | 无相关异常 |
| 交互有真实状态变化 | 登记/停用/刷新/重新加载通过 |
| 截图 | 下列三张已逐张查看，等待弹窗及加载过渡结束后采集 |

本机临时截图：[凭证停用](/private/tmp/service-onboarding-qa/real-credential-disabled.png)、[Shell 与 Demo](/private/tmp/service-onboarding-qa/real-shell-demo.png)、[接入诊断](/private/tmp/service-onboarding-qa/real-onboarding-status.png)。

## 验收边界与交付限制

- Nacos 关闭的真实隔离环境显示配置未知、没有前缀则不自动探测；不会把其他证据推导为配置成功。Nacos 配置读取、掉线、真实 HTTP 探测成功/错误服务/HTML200 的分类由外部配置源夹具与真实 JDK HTTP 传输验证。未宣称真实 Nacos 或共享部署验收。
- 运行状态只代表负载均衡选中实例；HTTP 探测只证明该次网关 OpenAPI 路由与声明身份，不代表所有业务授权或所有副本。
- 验收脚本已改为 clean package，避免源码未改的 Demo fat JAR 保留旧 SDK。最终读取 Demo 嵌套 JAR，状态 matcher 与 controller 均存在；详细诊断见 review-fixes.md。
- 外部 Lowcode 仅删除 `lowcode-sub-app/vite.config.ts` 一处 `apiPrefixes`，保留本地未提交变更；新 SDK/kit 兼容性以临时产物验证，未发布正式依赖或部署。补丁见 [lowcode-api-entry.patch](lowcode-api-entry.patch)。
- 集成分支保留本地，未推送、未合并 main；未迁移共享环境。
