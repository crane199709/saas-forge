# Console 原生本地开发

> 2026-09-22：旧双 Console、前端构建及浏览器验收入口已迁出。本页相关命令仅供历史追溯，不再是当前后端操作入口；服务端迁移、引导及专项服务验收仍保留。当前边界和待迁检查见 [迁出记录](acceptance/consoles-extraction.md)。

适用于 Issue #162，遵循 ADR 0043、0044。两个 Console 的进程由各自终端控制；受信 HTTPS Edge 和已有后端独立运行。浏览器仍访问 Controlled Browser Origin，经 Gateway 使用正式 API 和 Browser Session Slot。

## 一次性准备与契约更新

使用 Node 24.14.1、pnpm 11.22.0，以及根 POM 要求的 JDK。先在仓库根目录执行：

```bash
pnpm --dir consoles install --frozen-lockfile
pnpm --dir consoles run generate:api
pnpm --dir consoles run build:static-remote
bash scripts/local-https-development.sh setup
bash scripts/local-https-development.sh hosts
bash scripts/local-https-development.sh trust-ca
bash scripts/local-https-development.sh doctor
```

`hosts` 与 `trust-ca` 沿用各自的系统修改授权。证书、域名或信任检查未通过时先修复环境，不使用 TLS 绕过参数。静态 Remote 构建用于满足已有四域 Edge 的只读挂载，不增加 Remote 开发或验收范围。

`generate:api` 调用正式 Maven OpenAPI Generator，成功后在被 Git 忽略的 `.generated/` 内记录输入指纹。正式契约 `saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml`、根 POM 或 OpenAPI 模块 POM 变化后，再执行一次该命令。日常启动只检查指纹、生成清单及必需 TypeScript 文件，不执行 Maven、不安装依赖。缺失、过期或生成失败时，启动会退出并提示准备命令；不要手写 Client 或修改指纹绕过检查。

直接运行 Maven 仍可生成正式 Client，但首次原生开发须通过上述 `generate:api` 完成准备记录。修改契约后应停止并重新启动开发服务器，以重新执行预检。

## 独立 HTTPS 入口

完成准备后，即使两个 Vite 都未运行，也可以在仓库根目录执行：

```bash
bash scripts/local-https-development.sh start edge
bash scripts/local-https-development.sh status edge
```

命令只启动或复用当前项目的 HTTPS Edge，不启动 Console 或后端，不运行完整 Compose。Edge 未找到 Vite 或后端时，对应请求不能成功；`EDGE: RUNNING` 只表示入口进程就绪，不表示应用联调通过。

沿用已有 `deploy/compose/.secrets/local-service-replacement/api-target.json`：容器 Gateway 使用 `{"hostname":"gateway","port":8080}`，现有本机 Gateway 使用 `{"hostname":"host.docker.internal","port":8080}`。首次缺少此文件时默认准备容器 Gateway 目标；已有文件不会被覆盖。不在这里配置 IAM 或其他下游服务地址。确认目标 Gateway 及其依赖已经准备好，再做真实认证。

停止入口使用 `bash scripts/local-https-development.sh stop edge`。这会中断两个 Console 的 HTTPS/API/HMR 访问，但不会停止它们的 Vite 或后端进程。遇到未知 443 监听者或不兼容 Edge 时，命令拒绝接管；由开发者检查并处理冲突。

## 两个应用分别启动

终端一：

```bash
cd consoles/platform-console
pnpm run dev
```

终端二：

```bash
cd consoles/tenant-console-shell
pnpm run dev
```

启动日志会将 `https://platform.saas.forge.test/` 或 `https://console.saas.forge.test/` 标为“浏览器入口”；打开该 HTTPS 地址。`127.0.0.1:5173/5174` 仅显示为“内部监听（非浏览器入口）”。API 仍是 `https://api.saas.forge.test`，HMR 使用各自 HTTPS 域名下的 WSS。

日志直接输出到当前终端，`Ctrl+C` 只停止该终端的应用；另一 Console 和 Edge 继续运行。端口已被占用时 Vite 明确失败，不会改端口或接管既有进程。仓库根目录的 `pnpm --dir consoles run dev:platform` / `dev:tenant` 也只转发到同一个原生命令。

旧 `local-development.sh frontend ...` 和 `local-https-development.sh ... platform|tenant|all` 托管入口保留给既有集成验收。不要让它们与同端口的原生 Vite 同时运行，也不要用旧托管状态判断原生进程是否运行。

## 验证

本次开发入口相关检查：

```bash
node --test consoles/test/native-development.test.mjs scripts/test/https-edge-lifecycle.test.mjs
pnpm --dir consoles run typecheck
pnpm --dir consoles run test
```

实际验收需额外观察两个终端的启动日志、分别执行 `Ctrl+C`，并确认另一应用和 Edge 仍可访问。在两个受信页面中修改各自 React 组件，确认可见内容热更新、WSS 更新到达且页面未整页重载，随后恢复临时修改。

复用已有真实认证和安全测试；账号必须具有测试所需的 Platform 权限和 Tenant Membership，当前凭据只通过受限文件传入：

```bash
export SF_SESSION_EMAIL_FILE=/absolute/path/to/current-email
export SF_SESSION_PASSWORD_FILE=/absolute/path/to/current-password
export SF_SESSION_EVIDENCE_DIRECTORY=/absolute/path/to/evidence
export SF_SECURITY_EDGE_CONTAINER=compose-local-https-edge-1
NODE_USE_SYSTEM_CA=1 pnpm --dir consoles run verify:local:session-security
```

`SF_SECURITY_EDGE_CONTAINER` 应填写当前项目实际的 Edge 容器名或 ID；默认 Compose 项目名下如上所示。它用于读取被浏览器 CORS 隐藏的拒绝响应证据，需要 Docker 访问权限。

该测试保留 Gateway、Cookie、CORS、CSRF 和两个 Session Slot 的断言，不用 mock 登录或降低断言替代真实后端验收。失败时保留失败阶段与证据；局部检查通过不能代替浏览器安全验收或 CI 完整验证。
