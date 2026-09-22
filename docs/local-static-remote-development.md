# 第四域开发静态资源验收（Issue #156）

> 2026-09-22：旧双 Console、前端构建及浏览器验收入口已迁出。本页相关命令仅供历史追溯，不再是当前后端操作入口；服务端迁移、引导及专项服务验收仍保留。当前边界和待迁检查见 [迁出记录](acceptance/consoles-extraction.md)。

日常运行两个 Console 请使用 [Console 原生本地开发](native-console-development.md)。本页保留第四域静态资源专项验收与旧环境升级说明。

本切片遵循 ADR 0009、0038、0039，补齐 Local Browser Topology 的 `remote.saas.forge.test`。它不实现 Manifest、业务 Remote、Remote HMR，也不修改认证 API、Browser Session Slot 或数据库。静态文件是公开制品；CORS 是浏览器读取许可，不是私有下载鉴权。

## 准备与升级

从仓库根目录执行，使用既有 Node 24.14.1、pnpm 11.22.0 和已安装的 Console 依赖：

```bash
pnpm --dir consoles run build:static-remote
bash scripts/local-https-development.sh setup
bash scripts/local-https-development.sh hosts
bash scripts/local-https-development.sh trust-ca
bash scripts/local-https-development.sh doctor
```

- `setup` 在受限目录生成/复用证书，旧三域 leaf 会使用原有效 CA 重签，不要求更换 CA。运行中的 Edge 不热加载证书。
- `hosts` 确保四个域名指向 `127.0.0.1`；系统 hosts 和 Keychain 修改仍需分别交互授权，非交互脚本不能代为同意。
- `doctor` 报告缺失证书覆盖、域名、信任或工具链。不要使用 `ignoreHTTPSErrors`、`--ignore-certificate-errors`、`curl -k` 或 localhost 产品入口绕过阻塞。
- Remote 制品必须先构建；Compose 使用禁止自动创建源目录的只读 bind mount。日常启动不安装依赖或自动构建 Remote。

首次从旧三域 Edge 升级时，应安排两个 Console 的短暂入口中断。先核对当前项目与 `local-https-edge` 容器归属；新配置的 topology/routing 标签会让旧进程被判为不兼容，不能靠重复 `start` 假装路由已加载。由操作者明确授权后，仅重建此 Edge：

```bash
export SF_LOCAL_HTTPS_CERT="$PWD/deploy/compose/.secrets/local-https-development/server.pem"
export SF_LOCAL_HTTPS_KEY="$PWD/deploy/compose/.secrets/local-https-development/server.key"
export SF_LOCAL_HTTPS_API_TARGET_FILE="$PWD/deploy/compose/.secrets/local-service-replacement/api-target.json"
export SF_LOCAL_HTTPS_HOST_GID="$(id -g)"

docker compose --project-directory deploy/compose \
  -f deploy/compose/compose.yaml \
  -f deploy/compose/local-https-development.override.yaml \
  up --detach --no-deps --force-recreate local-https-edge

bash scripts/local-development.sh frontend start all
```

上述命令要求既有 API target 文件已由本地生命周期准备；全新安装使用正常 `frontend start all` 创建它并启动 Edge。不得对未知 443 监听者执行替换，不使用 `down`、删除卷、重启后端或修改 API target 解决静态资源问题。

## Tenant 入口与版本规则

打开 `https://console.saas.forge.test/acceptance/static-remote`，分别点击 **Load v1**、**Load v2**：

- 入口属于真实 Tenant 应用，仅在 Vite 开发模式或显式 `--mode static-acceptance` 构建中可达；默认产品构建剔除此入口，不加入产品导航。不需要登录，不创建第二个认证 Runtime。
- 模块从 `https://remote.saas.forge.test/static-acceptance/v1/remote.js` 或 `v2/remote.js` 导入并执行。相同版本的 `styles.css` 以 `anonymous` 加载；`image.svg` 以 `anonymous` 加载并解码。
- Remote 不接收业务 API 调用。跨 Origin module 使用浏览器默认 `same-origin` credentials，CSS/图片使用 `anonymous`，均不向 Remote 发送 Cookie；没有 Authorization 或 X-SF-CSRF 注入。
- 仅 `https://console.saas.forge.test` 获得精确 `Access-Control-Allow-Origin`，始终不返回 `Access-Control-Allow-Credentials`。Platform、其他来源和 `null` 不获得许可。仅支持 GET/HEAD，不提供凭据请求头预检许可。
- 精确版本/文件路径之外返回真实 404，不回退 Tenant HTML、不代理 Gateway。Remote 不提供 WebSocket/HMR。
- `consoles/static-remote-acceptance/checksums.json` 冻结两个版本的构建字节。构建先在临时目录校验全部 SHA-256，再交付到 `consoles/dist/static-remote-acceptance/`；校验失败保留原制品。已交付版本不得修改源文件或重写对应 checksum 来掩盖变化，包括工具链升级造成的字节变化；新内容使用新版本路径。

## 验证与证据

```bash
pnpm --dir consoles run test:boundaries
pnpm --dir consoles run verify:local:static-remote
```

Chromium 使用系统正常信任与真实域名，不启动替代 Console 或模拟产品服务。脚本验证：

1. Tenant 页面实际执行两个模块、计算样式分别呈现 7px/11px 边框、图片成功解码并具有独立尺寸；下载 200 不能代替这些断言。
2. 浏览器在 Remote 上预置非敏感测试 Cookie，仍验证资源请求不携带 Cookie、Authorization 或 X-SF-CSRF。CDP ExtraInfo 记录第四域请求路径、状态和精确 CORS 响应，即使浏览器拒绝读取响应也保留直接证据；不保存原始 headers、Cookie 值、Token、响应体或控制台原文。
3. Platform、非法 localhost 攻击探针和 opaque/null Origin 无法读取 Remote，且确实收到不含 CORS 许可的第四域响应；这些探针不是产品入口。
4. 双版本重复读取内容固定、彼此不同，缺失资源为 404 而非 HTML。
5. 两个 Console 正式域名的 Vite WSS 连接收到 connected 帧；API 正式域名的 JWKS 路径正常响应。完整双槽位认证、手工修改文件的 HMR 更新回归仍沿用 Console README 的原验收，不以此脚本替代。

结果写入 Git 忽略的 `.scratch/issue-156/local-static-remote.json`，包括失败阶段；失败不标记通过。缺少环境条件时先记录阻塞，取得明确授权后再修改运行环境并重跑。

## 后续 E2E 复用边界

- 共用 `build:static-remote` 生成的同一目录，不为 E2E 复制一套资源。
- `deploy/compose/local-https-development/remote-static.mjs` 的 HTTP handler 可由 E2E TLS Edge 在精确 Remote Host 下调用；用 `SF_REMOTE_STATIC_DIRECTORY` 指向同一只读制品目录。
- Fresh Compose 验收由 `scripts/verify-console-authentication-e2e.sh` 追加静态 Remote 验证：使用验收 Tenant 构建中的 `/acceptance/static-remote` 路由、同一 HTTP handler 和同一只读制品目录。
- `consoles/integration-test/static-remote-acceptance.mjs` 的呈现断言可复用，但开发 WSS 检查只属于开发证据，不能用于静态 E2E Console。

Fresh Compose 输出的 `EVIDENCE:` 目录在清理后保留。Chrome 写入 `static-remote-chrome.json`，记录静态 Remote 子测试的通过/失败状态、固定资源请求/响应元数据、模块/CSS/图片呈现结果和控制台错误类别。该记录只代表此子测试，不代表整轮验收；不包含凭据值、响应正文或原始控制台文本。可通过 `SF_BRAND_EVIDENCE_DIRECTORY` 指定目录；尚未观测到的请求信息不会当作无凭据或成功响应。

本说明中的开发证据不代表 Chrome Fresh Compose、CI、父规格 #155 或 MVP 四域条目整体完成；这些需要各自环境的直接证据。

## 跨浏览器聚合入口

当前 Chrome 开发验收入口为 `bash scripts/verify-console-authentication-e2e.sh --development`；Fresh Compose 沿用同一脚本原有参数。两套环境共用静态资源、安全探针与冻结制品 SHA-256 检查，分别留存直接证据。依赖、凭据文件、Edge 观测与 CI 结果判定见 [四域聚合验收说明](acceptance/issue-159-four-domain-matrix.md)。历史 #156/#157/#158 结果不能替代当前验收。
