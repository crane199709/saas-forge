# Issue #203：版本化 Client 与独立前端启动

日期：2026-09-15。状态：代码与制品已交付，**真实 Gateway 成功读取验收尚未完成，Issue 不应关闭**。

## 基线与发布

| 项目 | 基线 |
| --- | --- |
| 后端起点 | `afc9c6daaa98fa8e1dc5613c5ee20012665cf2c6` |
| Client 0.1.0 来源 | `68a8a74a769ae8c3110042176d9a3581895aedad` |
| Client 0.1.1 来源 | `6bf26865521dfdbd5cca4ab5635cf590c6610262` |
| 前端实现 | saas-forge-web `12eaf58`，起点 `f9cd9eed203a71d620521e7a398b37cee1f2e60f` |
| Soybean 上游 | `7613bd206cd42001b40e3eafceeb895dcbc277a8`，仍为前端祖先，MIT LICENSE 保留 |
| 工具 | Node 24.14.1 / pnpm 11.22.0 / JDK 17.0.12 / Chrome 153.0.8010.37 |

用户确认 npm 官方公开 registry，并在自己的终端完成登录和两次发布的二次验证。包名：`@crane199709/saas-forge-api-client`，registry：`https://registry.npmjs.org/`。已核实 public 权限、正式版本元数据、真实下载与安装；首次发布后的短暂 metadata 404 已恢复。

- `0.1.0` integrity：`sha512-+Tlm3ZL0lBZsro/xd+MnKkNE3xh6brL4lZ9eiuWrfQVLruvr6/Ghw3kjAA5qCTW4PYO1jzQrK5AbHo045HHrIQ==`。
- `0.1.1` integrity：`sha512-x6ovS9aBtW8u3oMOxyQPBWDF33hK+xO8DOaXtrijGqgSL/3X2ydn3Cs+zeicJ7M6CqUvHtWuY7TgmdaEYTor5w==`。
- 安装包的 `contract-source.json` 与上表提交一致，均为 `dirty: false`。0.1.1 增加构建输入摘要，正式契约不变。
- `v1.yaml` SHA-256：`7540df0e8bc58ab69b511278e7f84561d54c50dbf617f98423dbb449e0f549ec`；`common.yaml`：`2792189b86d588fd37cd168333cf1a58ceed71dd8c1b4cdbfb9844df895be3c9`。

本次仅在当前本地分支提交，未推送 GitHub。npm 制品已经公开发布；上述新增提交的远端可访问性仍需源码同步，不能假定 GitHub 已含这些提交。

## 已通过

1. 独立 npm 构建从正式 OpenAPI 生成 ESM 与声明文件；本地 tarball 在仓库外安装并成功导入 `Configuration`、`DiscoveryApi`。这项为本地制品检查，另有下列真实 registry 安装结果。
2. 新前端真实安装 0.1.0，类型检查及 `pnpm run build` 通过；随后显式升级 0.1.1，manifest 精确锁定，lockfile 保留 npm 完整性，未升级无关 Soybean 依赖。
3. 将前端源码复制到独立目录，目录中无后端源码、兄弟目录链接、已有 node_modules 或临时 Client 包。以空 npm 用户配置执行 `pnpm install --frozen-lockfile`，再执行类型检查、7 项测试及构建，全部通过。PATH 中放置会立即失败的 Java/Maven 同名命令，整个验证未调用它们。
4. 独立目录执行原生 `pnpm run dev` 成功，实际只启动前端 Vite，监听 127.0.0.1:5174。配置的浏览器入口为 `https://console.saas.forge.test`，API 为 `https://api.saas.forge.test`，无 API 代理。环境准备与前端进程分离；未重启、接管或替换用户后端服务。
5. JDK 17 的 Gateway 及必要依赖完整单元测试：SDK auth 21、route catalog 4、Gateway 43，均 0 失败、0 错误、0 跳过。Gateway JWKS/CORS 单文件先行验证 16 项通过。旧 Client 类型检查与原生开发回归 4 项通过。
6. 前端完整 ESLint、类型检查与 7 项客户端/配置测试通过；既有 pre-commit 类型和 lint 钩子通过。
7. 用户授权的独立临时 Chrome 配置目录验证：可信 HTTPS 页面、单 Console 路由、键盘语言切换、Tab 焦点顺序、Enter 操作、失败文案即时翻译、内部 HTTP 入口禁止公开读取。未读取日常浏览器配置，未忽略证书错误。
8. 无 API 配置的独立构建产物经 HTTPS 打开后，页面明确提示配置缺失，JWKS 请求数量为 0。
9. 单独标记的模拟浏览器验证通过：HTTP 403 来源拒绝、连接拒绝、10 秒超时与重试恢复。它们不代表真实后端业务成功。
10. Standards 与 Spec 并行静态审查均未发现需修复项；审查明确保留真实成功链路未完成状态。

## 已查明的失败与阻塞

真实 Chrome 从 Console 向 Gateway 发出正式 `getJwks`，请求无 Cookie 或 Authorization。HTTPS 受信，但运行中的 Gateway 响应没有 `Access-Control-Allow-Origin`，Chrome 实际阻止读取；页面按设计显示网络/CORS/证书排查提示。

用户已恢复 HTTPS Edge 的 443 端口。随后对照 Edge 与 Gateway 内部 8080 的只读响应，两者都缺少 CORS 头，确认不是仅凭页面猜测。当前运行实例未体现本次 CORS 修改；仍需用户在 IDE 重新运行 Gateway，再验证受控来源成功、未允许来源失败。不得为完成验收接管用户后端进程、伪造来源或绕过 Gateway。

实现中的 JWKS CORS 只允许既有受控 Console Origin、GET/HEAD/OPTIONS，并关闭该匿名操作的跨域凭据许可；单元测试已经覆盖受控来源与禁止来源。**测试不能代替运行环境成功证据。**

首次沙箱测试因本机端口/tsx IPC 被禁止而失败，允许对应本机测试能力后复跑通过。一次 Maven 空配置被解释为 `false` 的兼容回归已修复为独立 profile，旧入口和新包均复验通过。独立安装曾因缺失 allowBuilds 选择失败；沿用用户已有的明确选择后通过，未扩大其他依赖脚本权限。

## 验收边界与后续动作

- 真实公开成功读取尚缺；不能声明上述后端/Client/前端组合已完整兼容。
- 未验证统一登录、Cookie/刷新/退出或受保护业务，本票不将公开读取视为登录成功。
- 未执行 Fresh Compose、全后端服务集成、旧 Console 全工作区测试或新 CI 远端运行；本机按 Gateway/Client/新前端的实际影响范围验证。
- 发布及兼容规则见 [版本化 API Client](../versioned-api-client.md)。后续需同步已提交源码、让用户重启 Gateway，并补录真实 Chrome 成功与来源拒绝结果后再关闭 Issue。
