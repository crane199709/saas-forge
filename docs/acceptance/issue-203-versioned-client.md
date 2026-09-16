# Issue #203：版本化 Client 与独立前端启动

初验：2026-09-15；补验：2026-09-16。状态：**本票本机验收已通过，真实 Gateway 读取的 CORS 阻塞已解除**。代码与制品已交付，源码提交尚未推送。

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
10. Standards 与 Spec 并行静态审查均未发现需修复项；初轮审查保留了当时真实成功链路未完成状态，后续补验见下文。

## 历史失败与阻塞（2026-09-16 已补验）

2026-09-15 初验时，真实 Chrome 从 Console 向 Gateway 发出正式 `getJwks`。HTTPS 受信，但当时运行中的 Gateway 响应没有 `Access-Control-Allow-Origin`，Chrome 实际阻止读取；页面按设计显示网络/CORS/证书排查提示。

用户已恢复 HTTPS Edge 的 443 端口。随后对照 Edge 与 Gateway 内部 8080 的只读响应，两者都缺少 CORS 头，确认不是仅凭页面猜测。当时运行实例未体现本次 CORS 修改，验收等待用户在 IDE 重新运行 Gateway。用户于 2026-09-16 确认已重新运行，补验结果见下节。不得为完成验收接管用户后端进程、伪造来源或绕过 Gateway。

实现中的 JWKS CORS 只允许既有受控 Console Origin、GET/HEAD/OPTIONS，并关闭该匿名操作的跨域凭据许可；单元测试已经覆盖受控来源与禁止来源。**测试不能代替运行环境成功证据。**

首次沙箱测试因本机端口/tsx IPC 被禁止而失败，允许对应本机测试能力后复跑通过。一次 Maven 空配置被解释为 `false` 的兼容回归已修复为独立 profile，旧入口和新包均复验通过。独立安装曾因缺失 allowBuilds 选择失败；沿用用户已有的明确选择后通过，未扩大其他依赖脚本权限。

## 2026-09-16 真实 Chrome 补验

用户在 IDE 重新运行 Gateway 后，本任务只启动并在验收结束时停止前端 Vite，未接管 Gateway 或 HTTPS Edge。前端源基线为 `74ffbac`（实现 `12eaf58`），消费已发布 Client `0.1.1`；后端源工作区基线为 `c100bce`（CORS 实现 `68a8a74`）。此处记录源工作区基线，未将其冒充服务公开返回的构建提交号。

使用独立临时配置目录的桌面 Chrome `153.0.8010.48`，在北京时间 09:04 完成：

- 真实 Console 页面 `https://console.saas.forge.test/connection` 点击“检查连接”，通过正式类型化 Client 向真实 Gateway 读取 JWKS，HTTP 200，页面显示“连接成功，已读取公开验证密钥。此结果不代表已登录。”；无页面 JavaScript 错误。
- Chrome 的 secure context 为 true，未忽略证书错误；实际请求 Origin 为 `https://console.saas.forge.test`，完整请求头中无 Cookie、Authorization。响应允许来源精确匹配该 Origin，未包含 `Access-Control-Allow-Credentials`。
- 使用隔离浏览器中的空白 Remote Origin 页面夹具发起请求，Origin 由浏览器产生，为 `https://remote.saas.forge.test`。真实 Gateway 返回 HTTP 403，未返回允许来源头，浏览器无法读取响应。仅发起页面为夹具，**API 响应未模拟**；403 状态由 Chrome 网络协议记录，因为 CORS 拒绝时页面层无法读取该响应。
- 独立 HTTPS 请求同时确认禁止来源的错误码为 `BROWSER_REQUEST_REJECTED`。访问 Remote 根路径得到 404 的初次浏览器探测未产生有效 API 拒绝证据，不计为通过。

上述结果补齐本票公开读取与受控来源的真实边界验证。当前前端实现与 Client 0.1.1、上述后端源基线的公开读取组合通过；不推导统一登录或受保护业务兼容性。

## 验收边界与后续动作

- 真实公开读取成功与禁止来源拒绝已补验通过；兼容结论限于本票公开读取范围。
- 未验证统一登录、Cookie/刷新/退出或受保护业务，本票不将公开读取视为登录成功。
- 未执行 Fresh Compose、全后端服务集成、旧 Console 全工作区测试或新 CI 远端运行；本机按 Gateway/Client/新前端的实际影响范围验证。
- 发布及兼容规则见 [版本化 API Client](../versioned-api-client.md)。真实 Chrome 成功与来源拒绝证据已补齐；源码提交尚需同步远端，本任务未操作 GitHub Issue 状态。
