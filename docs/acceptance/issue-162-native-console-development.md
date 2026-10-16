# Issue #162 原生 Console 开发验收

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

2026-09-10 在 macOS、Node 24.14.1、pnpm 11.22.0、Chromium 151.0.7922.34 上执行。使用原有受信本地 CA、四域 hosts、HTTPS Edge 和真实 SaaS Forge 后端；未绕过 TLS，未修改认证代码或安全断言。

## 通过

| 验收项                | 直接证据                                                                                                                                                             |
| --------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 正式 API Client 准备  | `pnpm --dir consoles run generate:api` 实际调用 Maven OpenAPI Generator，构建成功并写入准备记录。                                                                    |
| 日常原生启动          | 分别在 `consoles/platform-console`、`consoles/tenant-console-shell` 执行 `pnpm run dev`，两个前台终端直接出现 Vite 日志，分别监听 5173、5174，启动未调用 Maven。     |
| 独立停止              | Platform 终端 Ctrl+C 后，5173 停止而 5174 与 443 保持监听，Tenant HTTPS 返回 200；Tenant 终端 Ctrl+C 后，5174 停止而 5173 与 443 保持监听，Platform HTTPS 返回 200。 |
| 独立 Edge             | 两个 Vite 均停止时，实际执行 `stop edge`、`start edge`，依次得到 `EDGE: STOPPED`、`EDGE: RUNNING`；5173/5174 均无监听，443 有监听。                                  |
| HTTPS 与真实 HMR      | 两个受信 HTTPS 页面均收到各自 WSS 的 `update` 消息，临时 React 可见标记出现，页面全局哨兵保留，证明没有整页重载；无框架错误覆盖层或应用错误，修改后恢复原始源码。    |
| 双 Console 真实认证   | 原有 `verify:local:session-security` 最终结果 `status=passed`、`stage=complete`、`errors=[]`；32 项安全探针通过。                                                    |
| Session Slot 生命周期 | 两个槽位都登录；Platform/Tenant 分别独立恢复；Platform 登出后 Tenant 仍有效，Tenant 登出后 Platform 仍有效。                                                         |

测试与检查：

- Console `pnpm run test` 完整套件：启动日志修正后重跑，102 项边界测试、266 项工作区测试通过，0 失败。
- `scripts/test/https-edge-lifecycle.test.mjs`：3 项通过。
- Console 工作区 `typecheck` 通过；新增脚本和测试的 ESLint、Prettier 检查通过，`git diff --check` 通过。
- TDD 覆盖缺失/过期/不完整 Client、生成失败使旧准备记录失效，以及独立 Edge 命令；失败后补实现再验证通过。
- code-review 两路审查：Standards 0 项发现，Spec 0 项代码发现；审查时待补的真实认证证据已由最终运行补齐。

用户复核发现默认 Vite 日志只显示内部 HTTP 地址，容易误导访问。已将两个应用的 URL 输出改为各自受控 HTTPS“浏览器入口”，实际 host:port 则标为“内部监听（非浏览器入口）”。新增两项真实 Vite Server 输出测试经过红→绿验证；两个应用类型检查、ESLint、格式检查和追加双轴审查均通过。

本机原始证据位于 `/private/tmp/saas-forge-issue-162/`：`lifecycle.json`、`hmr.json`、两个 `*-hmr.png`、`console-tests.log`、`auth-final.log`、`auth-final/browser-sessions.json` 及两个认证截图。该临时目录不提交，以上表格保留关键运行结果；复现步骤原见 `docs/native-console-development.md`，该文档已删除，按[历史追溯](console-history-reproduction.md)从 Git 历史复现。

## 已解决的环境失败

首次使用本机 Gateway，以及切换到原有 Gateway 容器后，安全测试均在 `tenant-unlisted-method-preflight/refresh` 观察到 502，而契约要求 403。只读提取容器 JAR 后，确认它不含仓库已有的 `DefaultCorsProcessor` 修复。经用户授权切换 Gateway 后，使用当前 JAR 更新这一个 Gateway 容器；该 JAR 的 39 个 Gateway 编译类与当前编译目录逐一一致。更新后同一预检实际返回 403。

后续一次运行因缺少 `SF_SECURITY_EDGE_CONTAINER` 而无法补齐被 CORS 隐藏的直接拒绝证据。补齐实际 Edge 容器名后重跑完整原有测试，通过全部安全探针和会话生命周期。此前失败记录保留，未将失败计为通过。

## 未执行范围

本次完成 Issue #162 的本机原生开发验收；未执行 Fresh Compose、多浏览器矩阵、全部后端 Maven 测试或 CI 完整流水线。没有新增 Remote 业务能力，已有安全测试内部沿用的 Remote 探针保持原样。
