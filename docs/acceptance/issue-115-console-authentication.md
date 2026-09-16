# Issue #115：Console 真实产品聚合验收

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

> 当前阶段范围已由 [ADR 0046](../adr/0046-development-supports-chrome-and-jdk17.md) 调整为桌面 Chrome 当前稳定版与 JDK 17；Chromium 保留日常功能与视觉测试。本文旧矩阵的执行结果属于历史证据，不作为当前多浏览器或 JDK 21 要求。现行复现入口见 [本地验证说明](../local-verification.md)。

状态：**2026-09-03，提交 `5023f24` 的 Verify 与完整五渠道产品聚合均已通过。** CI 使用已获批准的 `saas.forge.example.com` 对照根域；本地仍使用 `saas.forge.test`。远端 Issue 原有固定域名条款尚未调整，#115 / #108 及 MVP 完成状态未变更。

| 最新门禁 | 当前直接结果 |
| --- | --- |
| 本地 Chromium / WebKit / Chrome 真实产品 | 各 16 通过，0 失败，0 跳过；历史本地聚合补跑曾遇到 TLS 连接中断 |
| CI 五渠道真实产品 | `5023f24` 的 Firefox、WebKit、Chromium、Chrome、Edge 各 16/16，0 失败/跳过 |
| 五渠道真实 TLS 预检 | 全部通过；开启正常证书验证 |
| CI Verify | 全部通过：四个兼容渠道、JDK 17/21、Nacos、生命周期 E2E |
| 完整产品聚合 | Maven/workspace、独立生产构建、五次 Fresh Compose / TLS / 产品测试和四个兼容门禁全部通过 |

直接结果：[Verify](https://github.com/crane199709/saas-forge/actions/runs/33650682545)、[完整产品聚合](https://github.com/crane199709/saas-forge/actions/runs/33650682486)。以下历史记录保留各轮失败、修复与证据范围。

## 已确认的测试边界

1. 两个 Console 的独立生产构建，经受信 TLS、真实 Gateway、IAM 及相关服务运行。
2. 通过浏览器操作、路由、HTTP、Cookie、存储和消息观察行为；不读取 Runtime 私有字段。
3. Runtime、类型化 Client 与 Gateway/IAM 的已有公共接口保留聚焦故障和拒绝分支证据。

每轮只新增一条失败测试及其必要实现。首条是两个生产 Console 通过正式 TLS Origin 展示各自登录页；该前提通过后，才继续双槽位同时登录和后续行为。

第二条用例通过正式页面完成 Platform 首次改密后重新登录，为同一 Identity 准备一个 Tenant Membership，再在同一浏览器 Context 登录 Tenant，观察两个槽位的独立恢复与退出。Tenant 业务夹具由 Node 侧正式 Platform API 建立；夹具请求本身不作为浏览器认证证据。初始密码从受限文件注入，新密码和捕获的 Access Token 只用于测试进程内存，不保存浏览器 storageState 或请求响应体。

## 入口与环境约束

- `scripts/verify-console-authentication-e2e.sh --preflight`：检查固定 Node/pnpm、Docker、证书材料、DNS、443 监听冲突与当前执行环境要求的浏览器渠道，并以回环临时 HTTPS 验证四个 Host 的浏览器证书信任。`SF_ACCEPTANCE_TARGET=local`（默认）与 `ci` 均只要求 Chrome。
- `scripts/verify-console-authentication-e2e.sh --product`：用于 TDD 重跑当前产品切片及当前环境的浏览器门禁，复用已有 JAR/dist，不重跑 Maven/workspace 门禁；不能作为完整聚合命令的成功证据。
- 产品渠道已固定为 Chrome，无需设置 `SF_PRODUCT_CHANNEL`；旧变量仅接受 `chrome` 且不再跳过 Chrome 消费者门禁，其他值在环境初始化前失败。
- `scripts/verify-console-authentication-e2e.sh`：预检成功后才执行 Maven/Console 验证、构建独立应用镜像、创建全新随机 Compose 项目、初始化 Signing Key/引导账户、启动四个 TLS Origin 并运行现有产品切片。
- 当前聚合脚本按实际命令范围返回结果：环境产品/兼容、完整 Maven/workspace/Fresh Compose 分别记账；任何用例或门禁失败仍返回非零。
- `deploy/compose/console-authentication.override.yaml`：后端端口不向宿主发布；两个 Console 分别挂载生产 `dist`；Node 使用 workspace 已固定的 `24.14.1` 版本；TLS 只发布 `127.0.0.1:443`。
- `deploy/compose/console-authentication/serve.mjs`：仅供验收的静态服务及固定 Host 代理，不是生产部署通用代理。API 请求的 Origin、Cookie、Fetch Metadata 原样转发，不补造浏览器安全头。
- 所有数据卷、网络及应用镜像属于本次随机项目；不使用开发 `.env` 或开发凭据，不覆盖已有本地应用镜像。结束时只清理该项目的资源。
- 每次 Chrome 产品验收使用全新随机项目数据卷并重新引导账户，从 Initial Credential Session 验证首次改密，不复用已修改的密码或会话数据。
- 不自动修改系统 hosts、信任库或安装浏览器；不使用 `ignoreHTTPSErrors`、HTTP 降级或模拟 API 代替正常产品路径。
- `.github/workflows/console-authentication-e2e.yml`：独立的真实产品任务，在临时 Ubuntu runner 上安装全部浏览器、建立本次专属 CA、系统/NSS/Firefox 信任、固定 hosts 和 Fresh Compose 环境。仅上传或保留脱敏结果，不上传证书私钥。
- 本地验证和 CI 分开记账：用户已确认 Firefox/Edge 只在 CI 执行。本地成功不能代替这两个渠道的远端运行结果。既有 `verify.yml` 中的 Design System/模拟服务浏览器任务也不代替真实产品任务。

执行前提供以下环境变量，证书须覆盖三个名称且已被每个目标浏览器信任：

```sh
export SF_ACCEPTANCE_TLS_CERT=/absolute/path/to/fullchain.pem
export SF_ACCEPTANCE_TLS_KEY=/absolute/path/to/private-key.pem
export NODE_EXTRA_CA_CERTS=/absolute/path/to/root-ca.pem
mise exec node@24.14.1 -- bash scripts/verify-console-authentication-e2e.sh --preflight
mise exec node@24.14.1 -- bash scripts/verify-console-authentication-e2e.sh
```

本地默认域名解析如下；经确认的 CI 对照使用同样的三个前缀与 `saas.forge.example.com` 根域：

```text
127.0.0.1 platform.saas.forge.test console.saas.forge.test api.saas.forge.test
```

预检中的证书匹配/有效期检查不等于浏览器信任通过；必须由各浏览器开启正常证书验证后实际导航证明。预检检查 443 是否已有监听者，实际端口绑定由 Docker 完成；非 root Node 不能绑定低端口不应误判为 Docker 部署失败。

Node 侧夹具请求同样保持证书验证；`NODE_EXTRA_CA_CERTS` 指向公开 CA 证书，使 Node 信任本次 HTTPS API。该环境变量不能替代各浏览器的信任库配置。

## 2026-09-02 的直接观察

环境：本机 macOS，Node `24.14.1`，Docker Server `29.7.2`。GitHub #109–#114 当时均为 CLOSED；#115 为 OPEN。

| 检查                            | 结果                         | 直接证据                                                                                                                               |
| ------------------------------- | ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| 首条真实 Chromium 产品测试      | RED，1 条失败，0 条跳过      | 在 `platform.saas.forge.test` 导航时得到 `net::ERR_CONNECTION_CLOSED`，未到达登录页                                                     |
| 本地 TLS 材料                   | 阻塞                         | 未提供 `SF_ACCEPTANCE_TLS_CERT` / `SF_ACCEPTANCE_TLS_KEY`                                                                              |
| 三域名解析                      | 阻塞                         | Platform、Console、API 分别解析为 `198.18.1.4`、`198.18.1.5`、`198.18.1.6`，并非要求的回环地址                                         |
| Chromium / Chrome / WebKit 启动 | 通过启动预检，产品行为未执行 | Playwright 启动并关闭成功；不能当作认证通过                                                                                            |
| Firefox 启动                    | 阻塞                         | 缓存存在，但启动日志出现 `sandbox_extension_issue_file_to_process ... Operation not permitted`，随后启动超时；没有关闭浏览器沙箱绕过   |
| Microsoft Edge                  | 阻塞                         | 标准应用目录不存在，Playwright `msedge` 渠道启动失败                                                                                   |
| Compose 合并配置                | 静态检查通过                 | 使用临时占位环境运行 `docker compose config --format json`，检查后端端口隔离、独立应用镜像名、只读 Console 与回环 TLS 端口；未启动容器 |
| Shell / JavaScript              | 语法检查通过                 | `bash -n`、`node --check`                                                                                                              |

首条 red 命令：

```sh
cd consoles
mise exec node@24.14.1 -- node --test integration-test/console-authentication.test.mjs
```

本轮没有创建、启动或删除 Compose 数据卷，没有修改系统 DNS/hosts/信任库，没有提交代码或更新远端验收勾选。

## 2026-09-02：用户授权本地 TLS/hosts，Firefox 与 Edge 改由 CI

上述环境阻塞记录保留为首轮原始证据。用户随后批准本地证书信任及 hosts 配置，并确认不在本机安装或排查 Firefox/Edge。

- 已生成只适用于 `.saas.forge.test` 的本地 CA 和覆盖三个域名的服务器证书；私钥保存在 Git 忽略的 `deploy/compose/.secrets/console-authentication/` 中。
- CA 已加入当前用户钥匙串信任；服务器证书有效期 90 天，CA 有效期 365 天。
- 已备份 `/etc/hosts` 至 `/etc/hosts.saas-forge-backup-20260902141459`，然后追加三个域名到 `127.0.0.1` 的映射，保留已有条目。
- `SF_ACCEPTANCE_TARGET=local` 预检通过：Chromium `151.0.7922.34`、Chrome `152.0.7977.66`、WebKit `26.5` 均可启动。
- 用临时 HTTPS 服务实际验证以上三个浏览器对全部三个域名的信任，`ignoreHTTPSErrors=false`，HTTP 200 且 `isSecureContext=true`；这仅证明 DNS/证书环境，不是认证产品通过证据。
- 已准备 CI workflow，尚未提交或运行远端 CI。Firefox/Edge 的真实产品验收保持未完成。

CI 的 Linux 浏览器信任按官方入口配置：[Chromium NSS](https://chromium.googlesource.com/chromium/src.git/+/refs/heads/main/docs/linux/cert_management.md)、[Firefox Certificates policy](https://mozilla.github.io/policy-templates/#certificates)、[Playwright CI](https://playwright.dev/docs/ci)。CI 不接收本机 CA 私钥，每次自行生成短期材料并在任务结束时清理。

## 同日后续本地验证

- 完整 `./mvnw --batch-mode --no-transfer-progress verify` 为 `BUILD SUCCESS`；包含 contracts/openapi 所绑定的 Console workspace 门禁。单独执行的 Console `verify` 也通过；聚合脚本已去除这项重复执行。
- 已从随机项目的全新 PostgreSQL 数据卷完成 Signing Key、Platform 管理员和服务 Client 引导，启动真实 Redis、Nacos、Kafka、Gateway、IAM、Tenant Access、Entitlement、Audit 和两个 Console。
- 首轮产品测试发现 `compose up --wait` 只证明进程 running，不能证明 TLS listener/静态页/Gateway 已就绪。新增 healthcheck 等待入口监听、两个真实静态页面和 Gateway/JWKS 返回成功，再启动浏览器。
- 构建工件权限为 `0600` / 部分目录 `0700`；本机 Docker Desktop 容器实测可读取，因此不是本次本机失败原因。为使 Linux bind mount 保持相同权限语义，静态服务使用宿主 UID/GID 只读消费工件。
- 密码字段的标签包含必填标记，修正测试定位以对齐现有表单测试，并继续断言输入类型为 `password`；没有修改产品表单。
- 在项目 `saas-forge-console-1788331432-27647-b4b1b1` 中，首条产品测试分别在 **Chromium、WebKit、Chrome 通过**：三个渠道均实际经受信 TLS 到达 Platform/Tenant 的独立登录页，不模拟正常 API。
- 前一次定位过程直接捕获 Platform `runtime-config.json → 200`、真实 `auth/refresh → 401` 和“登录 Platform Console”标题；诊断不记录请求头、Cookie 或响应体。
- `test:browser:chrome` 通过；`test:browser:webkit` 的既有 Design System 展示矩阵为 **3 通过、3 失败、3 跳过**，三项失败均为 `toHaveFocus()`：48rem 分栏焦点、键盘/危险确认流程、390px/360px 表单表格确认流程。跳过项仍需按原测试的视觉开关单独核对，不能把本次完整 WebKit 门禁标为成功。
- 最终聚合退出码非零，#115 保持未完成。失败日志和本轮生成的截图已移至本机受限临时诊断目录，未作为源码或公开 CI 制品提交。
- 远端 CI 尚未执行，没有把本地结果推断成 Firefox、Edge 或 Linux WebKit 已通过。

## 同日第二条切片：首次改密与双槽位独立会话

- 命令：提供现有 TLS 材料与 `NODE_EXTRA_CA_CERTS`，执行 `mise exec node@24.14.1 -- bash scripts/verify-console-authentication-e2e.sh --product`。
- 项目：`saas-forge-console-1788332755-30783-11eeb7`。每个渠道执行前重建本项目数据卷并重新引导 Platform 管理员；未复用开发数据。
- Chromium、WebKit、Chrome 均通过两条产品用例：原 TLS 登录入口，以及本轮新增的首次改密与双槽位独立会话。
- 直接观察：首次登录返回 `PASSWORD_CHANGE_REQUIRED` 且不包含 Access Token；页面改密返回 204 后回到登录页；新密码重新登录成功。通过正式 Platform API 为同一 Identity 创建一个可用 Tenant Membership 后，Tenant 登录直接进入工作台。
- 在同一浏览器 Context 中观察到两份不同的 Access Token 和两枚独立 Refresh Cookie；两枚 Cookie 均为 Secure、HttpOnly、API Host-only、根路径。未打印其值。
- 两个 Console 分别通过浏览器刷新恢复；退出 Platform 后 Tenant 仍能恢复，Platform 保持匿名；重新登录 Platform 再退出 Tenant 后，Platform 仍能恢复，Tenant 保持匿名。两页均未捕获 `pageerror`。
- 新用例首次执行即通过，说明既有认证实现满足本条路径；本轮没有为了制造 RED 改动产品实现，也没有新增产品 API 或认证测试后门。修改范围为真实产品测试、必要夹具和按渠道重建环境的脚本。
- 本条不证明全部 Token 持久化/消息安全、两个 Runtime 的全部竞争行为、受保护子路由、多 Membership、Tenant Switch 或故障恢复要求；这些仍保留为未完成项。
- WebKit 焦点对照实验使用临时原生 HTML：同样从第一个按钮开始，WebKit 的 Tab 到输入框、Option+Tab 到第二按钮；鼠标点击第二按钮后焦点为 BODY。Chromium 的 Tab 和点击均到第二按钮。此证据说明既有测试包含浏览器焦点行为差异，尚未完成对应测试或组件修正；未改系统设置、未放宽断言。
- 已核对此前 WebKit 的三个跳过项均为 `SF_VISUAL_SNAPSHOTS=false` 控制的视觉快照：五视口矩阵、标准/全宽/窄屏栅格、主辅分栏快照。键盘/焦点断言没有被跳过。
- 本轮 `test:browser:chrome` 通过；`test:browser:webkit` 仍为 3 通过、3 个同样的焦点失败、3 个视觉快照跳过，聚合退出码为 1。失败日志与截图移入受限临时目录；结束后按项目标签检查容器和数据卷均无残留。Shell/Node 语法、新增测试的 ESLint/Prettier 检查通过。未重跑 Maven/workspace，未提交或推送，远端 CI 尚未执行。

## 同日继续：多 Membership、切换与 WebKit 焦点

- 多 Membership 首轮 RED：第二个 Tenant 的夹具重复创建 `max_users`，正式 API 返回 409。修正为复用已激活 Plan 后，项目 `saas-forge-console-1788333433-33548-164dfa` 的 Chromium 产品测试通过：登录不返回 Access Token，先展示两个 Membership，选择第二个后才进入工作台，冷启动恢复成功。
- 正常切换下一条用例在项目 `saas-forge-console-1788333646-34499-bd9f5c` 的 Chromium 通过：按浏览器响应事件记录精确顺序 `tenant-switches:204 → refresh:200`，响应中的 Tenant ID、工作台根路由和全局导航名称切换到目标 Tenant，刷新后保持，Platform 会话仍可用。
- 刷新网络故障用例在项目 `saas-forge-console-1788333826-35382-e4736f` 的 Chromium 通过：真实切换提交后，仅中断该页第一次 Refresh 网络请求；界面展示“Tenant 切换已提交”和恢复操作，工作台及全局导航均隐藏。公共 API 使用切换前 Token 读取 `/api/v1/auth/context` 返回 401。点击“重试完成切换”后真实 Refresh 返回 200、新 Token 和目标 Tenant，上下文恢复；切换提交请求总计一次。
- 这些切换夹具尚未写入自定义 Tenant Brand Profile，当前导航名称证明来自真实 Tenant 上下文的默认回退；不把它当作自定义品牌颜色、Logo、Favicon 的完整验收。
- WebKit 键盘修正限定于测试：仅 macOS WebKit 使用实际 Option+Tab，其余平台和引擎继续使用 Tab。保持原焦点、DOM 顺序和布局断言，不修改系统设置、不跳过键盘用例。按键差异依据本机原生 HTML 对照实验及 [Apple Safari 键盘说明](https://support.apple.com/guide/safari/keyboard-shortcuts-and-gestures-cpsh003/mac)。
- 展示册两个原生按钮在打开编辑/危险确认弹窗前显式聚焦自身，避免 WebKit 点击不聚焦导致弹窗关闭后回到 BODY；未改变共享组件公开 API。原失败断言已转绿。
- 完整 `test:browser:webkit` 已通过：展示矩阵 6 通过/3 个视觉快照跳过，消费者矩阵 6 通过/1 个视觉快照跳过，原生多标签页测试 1 通过/0 跳过。该既有多标签页测试仍使用模拟服务，不能替代 #115 的真实后端竞争证据。
- Chromium 展示矩阵 9/9 通过，包含既有视觉快照；Console 递归类型检查通过，改动文件 ESLint/Prettier 与 `git diff --check` 通过。
- 项目 `saas-forge-console-1788333826-35382-e4736f` 的完整本地产品矩阵已通过：Chromium、WebKit、Chrome 均从各自重建的数据卷执行登录入口、首次改密/双槽位、多 Membership、正常切换与切换后的网络故障恢复。Firefox/Edge 未在本机执行，远端 CI 尚未运行。

## 同日继续：默认入口的原生多标签页协调

- 真实 Chromium 并发恢复用例首先失败于两页未同时恢复工作台。排查发现两个 Console 的默认 `realm` 为 `{}`，使生产入口无法访问原生 Web Locks、BroadcastChannel 和 localStorage；既有多标签页夹具显式传入 `globalThis`，未覆盖这个接线问题。
- 新增 `console-default-realm.test.mjs`，通过两个真实默认应用入口、原生标签页及受控 HTTP 响应验证同一次并发恢复只有一次 Refresh。旧 Tenant/Platform 入口各自出现 `2 !== 1`；先修 Tenant 后仅 Tenant 转绿，再修 Platform 后两者通过。
- 两个默认入口改为 `globalThis`。测试启动屏障等两页都开始会话操作后才释放首个响应，避免将页面尚未启动误判为协调失败；没有读取 Runtime 私有状态或改写原生锁的执行结果。
- 完整 Console `verify:workspace` 通过，包括类型、lint、格式、单元/边界测试、Chromium 视觉与消费者门禁、三条原生标签页测试及两个独立生产构建。该轮未重跑后端 Maven。
- 项目 `saas-forge-console-1788335400-41907-71bd84` 使用新生产构建和全新数据卷，Chromium 产品切片通过：两页并发刷新后均恢复目标 Tenant 工作台；登出与另一页刷新竞争后，两页均保持匿名，Platform 仍可恢复。临时 `[DEBUG-115-tabs]` 输出已移除。
- 此前项目 `saas-forge-console-1788334485-37440-a1dc7e`、`saas-forge-console-1788335019-40832-d95989` 在登录页面加载前得到 `ERR_CONNECTION_CLOSED`，没有触及会话逻辑。最小 Docker HTTPS 服务连续三次返回 200；后续真实产品重跑通过，但尚未确定前两次连接中断的原因，不将它们归因于 Realm 修复。
- 产品阶段失败时，脚本将入口容器状态和 TLS 服务日志保存在本机受限诊断目录，仍不输出或上传原始内容。聚合入口继续返回 `INCOMPLETE`，Firefox/Edge 仍待远端 CI。

## 同日继续：浏览器安全拒绝的观察边界

- 项目 `saas-forge-console-1788335592-42507-5815c0` 再次通过真实 Chromium 会话与多标签页用例，并发恢复直接观察到一次 Refresh 200。
- 安全负向测试首次失败于 `Failed to fetch`。项目 `saas-forge-console-1788335825-43270-8747bb` 的受限诊断确认：错误 Intent 返回可读的 403，错误 CSRF 在浏览器端不暴露响应状态。
- 最小跨 Origin 探针确认无 CORS 许可的拒绝响应只触发 CORS 控制台错误和请求失败。Gateway 的 CSRF/Content-Type 防护在 CORS 过滤器之前，因此浏览器用例检查明确的 CORS 拒绝信号，并在每次负向请求前后确认合法请求返回 204，以排除服务或网络中断。
- 浏览器不会由测试注入 Origin、Cookie、Fetch Metadata；具体服务端拒绝分支由现有 Gateway/IAM 公共 HTTP/安全边界测试佐证。不能从页面的 `Failed to fetch` 单独推断 HTTP 403。

- 跨站用例使用真实 sandbox iframe。Chromium 在项目 `saas-forge-console-1788336855-49452-4cff8c` / `saas-forge-console-1788337069-50145-64ccad` 中已收到真实 403，Gateway 分别记录 `CSRF`、`CONTENT_TYPE`、`ORIGIN`，IAM 记录 `ORIGIN_SLOT`。两轮最后仍因 Playwright 未暴露 opaque 请求安全头而失败，不算全套通过。
- 为保持严格的入站头断言，验收专用 TLS 代理仅对 `/api/v1/auth/logout?acceptanceProbe=<随机 UUID>` 记录探针 ID 和两个枚举：`opaque/other`、`cross-site/other`。请求照常原样转发；记录不包含任意 Origin 文本、Cookie、Token、其他头或请求体。测试通过本次随机容器日志读取唯一探针记录，并同时断言浏览器 opaque Origin、不可读响应与真实 HTTP 403。

## 同日继续：默认文档的 favicon 生命周期

- 新的公开 App 回归在没有初始 `link[rel~=icon]` 的文档中失败：Tenant 品牌已显示，但 favicon 为 `undefined`。该前提与当前 Tenant 生产 `index.html` 一致；原测试先创建图标标签，未覆盖此场景。
- Tenant App 现在仅在品牌提供 favicon 时按需创建标签；品牌变化、退出或卸载时移除自行创建的标签，已有宿主图标则恢复原 href。
- 修复后 Tenant App 11 条测试全部通过，包括新建/退出清理和已有图标切换恢复；类型、lint、格式检查通过。包含该修复的完整 Console `verify:workspace` 随后通过并重建两个生产包；真实品牌夹具仍待完成，不能以这项受控 HTTP 回归替代。
- 项目 `saas-forge-console-1788336297-45793-c02471` 的真实 Chromium 安全负向已通过：错误 Intent 403，以及 CSRF/非 JSON 的明确 CORS 拒绝和前后合法 204 对照。该轮会话用例在夹具创建 `quota-definitions` 时遇到 403，未进入 Tenant 路径，整轮保持失败；原因尚未确定，后续失败增加脱敏 Problem code 与受限 Gateway/IAM/Entitlement 日志取证。

## 同日继续：三渠道安全聚合与锁交接可见性

- 项目 `saas-forge-console-1788337396-50840-6b7fa2` 的 Chromium、WebKit、Chrome 全部通过现有真实产品用例：TLS 登录、首次改密与双槽位、多 Membership、切换及恢复、原生并发恢复/登出竞争、错误 Intent/CSRF/Content-Type 拒绝，以及 opaque iframe 跨站请求。代理探针直接断言入站 opaque Origin 和 `cross-site`，所有渠道均得到 403。
- 该轮后续 Chrome 兼容门禁失败于默认 Platform 入口快速回归偶发两次 Refresh；因此整轮未通过，也没有执行其后的 WebKit 兼容命令。真实产品三渠道成功与兼容门禁失败分别记账。
- 加入非敏感时序诊断后，八轮上限中的第四轮复现：第二页取得锁并发出 Refresh 后，广播和 storage 事件才在约两毫秒内到达。没有出现一秒交接超时；导航事件属于同一文档，诊断状态未因文档重载重置。
- 公开 Runtime 回归以延迟广播和旧存储快照稳定复现 `2 != 1`。修复使用原子 `ifAvailable` 区分是否发生锁竞争：无竞争直接执行；发生竞争时，即使本页存储快照未更新，也保留现有一秒广播交接窗口。超时仍回退真实 Cookie/IAM Lease，不添加自制锁或持久化 Token。
- 新回归转绿，Runtime 106 条测试、类型、lint 与格式检查通过；移除诊断钩子后，原始 Chrome 原生标签页测试连续八轮通过。随后完整 Console `verify:workspace` 通过并重建两个生产包。
- 新生产包的真实 `logoutPending`、响应丢失和迟到广播切片正在补充；前述三渠道产品证据发生在此次锁交接修复之前，不能替代修复后的真实产品验证。

## 同日继续：退出未知、真实品牌与 IAM Lease 回退

- 项目 `saas-forge-console-1788338933-56603-12fad7` 使用锁交接修复后的生产包，真实 Chromium 通过退出响应丢失场景：IAM 已返回 204 后中断响应，两页进入 `logoutPending`；另一页重载不发 Refresh；显式重试复用原幂等键并清除 pending。重新发送先前捕获的原生认证广播，两页仍匿名，持久代次不倒退，Platform 仍可恢复。
- 项目 `saas-forge-console-1788339156-57274-f8c993` 的 Chromium 进一步通过真实品牌切换：仅在隔离数据库注入两份 Tenant Brand Profile 读模型夹具，断言均来自页面及正式 API。导航名称、主色、favicon 随 Tenant 切换，冷恢复保持，退出移除自行创建的 favicon。未以此声称可选 Logo 已验收。
- 项目 `saas-forge-console-1788339488-58061-fb5d29` 的 Chromium 通过强制能力不可用路径：在公开浏览器边界移除 Web Locks 和 BroadcastChannel，且断言确已不可用；两页独立并发 Refresh 得到真实 200 / `409 REFRESH_ROTATION_IN_PROGRESS`。提前重试无请求，按真实 Retry-After 等待后复用原幂等键恢复；退出后另一页冷启动保持匿名。
- 三轮均重建本项目数据卷并完成清理，未修改 IAM Lease 时长、Cookie 或服务端判定。聚焦命令继续以 `INCOMPLETE` 返回，其他渠道与聚合项仍待验证。

## 同日继续：生产错误、无障碍与存储审计

- 项目 `saas-forge-console-1788339694-58711-afdf89` 的真实根渲染故障测试 RED：安全错误页已出现，但 React 默认控制台输出仍包含原始 Error。两个 `createRoot` 入口现在仅在生产环境覆盖 `onCaughtError`，保留开发环境默认诊断；两份生产包的快速公开边界回归转绿。
- 路由错误的公开 Shell 回归随后证明焦点仍停在 BODY。错误页改为复用已有 `ShellPageTitle`，提供主标题焦点及 polite/atomic 读屏通知；17 条 Shell 测试、类型、lint、格式检查通过。
- 项目 `saas-forge-console-1788340013-60183-f9c211` 在所有产品测试加载登录页前再次遇到 `ERR_CONNECTION_CLOSED`，未验证上述改动。新增宿主三个 HTTPS 入口的正常证书/200 就绪检查；第一次检查未就绪，随后明确使用与容器健康检查一致的 Accept 类型。未确定此前间歇中断的根因，不能宣称已修复。
- 项目 `saas-forge-console-1788340394-61690-8ba837` 的宿主 HTTPS 检查及全部现有 Chromium 产品用例通过：根错误安全重载、受保护 OAuth Client 导航/刷新、路由错误隔离及返回首页、标题焦点和读屏状态均成立。两个生产入口的原始 Error 标记未出现在 UI 或浏览器诊断。
- 同轮审计三个 Origin 的 localStorage（仅代次/pending）、空 sessionStorage、空 IndexedDB、空脚本可读 Cookie；实际原生广播只含协议允许字段且代次不倒退。浏览器诊断及 Gateway、IAM、Tenant Access、Entitlement、Audit、两个 Console、TLS 代理的 stdout/stderr 均未包含检查的凭据、JWT 或 Membership 名称；断言只输出布尔值。
- 根错误页在 390px 宽度验证无横向溢出、assertive 通知和键盘重载。其他完整认证路径的窄屏键盘覆盖仍待补充；同轮未执行 Firefox、Edge 或其他本地渠道。

## 同日继续：真实公开 Client 暴露的 CSRF 接线遗漏

- OAuth Client 页面目前只有导航入口，因此额外构建仅供验收的公开 Runtime/Client 消费者，挂载到同一受控 Platform Origin；它连接真实 Gateway/IAM，不进入两个 Console 的发布包，不读取 Runtime 私有字段。此接口证据单独记账，不称为已有业务页面操作。
- 验收夹具补齐公开工厂必需的原生 fetch 参数后，项目 `saas-forge-console-1788340816-63559-b58877` 的真实创建仍失败。受限 Gateway 日志给出精确拒绝原因 `path=/api/v1/platform/oauth-clients reason=CSRF`；后续项目 `saas-forge-console-1788341336-65176-edd981` 在旧构建中重现浏览器 `NETWORK_UNAVAILABLE`（拒绝发生在 CORS 前）。
- 公开 Runtime 回归先 RED：受控浏览器写请求边界拒绝缺失标记的 typed mutation。共享传输层现在对写方法统一附加 `X-SF-CSRF: 1`，继续让浏览器管理 Origin/Fetch Metadata，不增加消费者参数。Runtime 107 条测试及类型检查通过，修正测试的 async lint 后 lint 也通过。
- 新生产包和公开接口包的真实重跑进行中；只有真实创建、一次 401 的 Refresh/GET 重放和响应丢失后的同键重试通过后，才将该项记为产品证据。
- 项目 `saas-forge-console-1788341039-64380-7ba0b0` 在宿主 HTTPS 就绪检查中记录三个 Origin 均为 `ECONNRESET`。本机存在系统代理但尚未证明因果。临时 TLS 对照容器被自动审批以私钥挂载/443 风险拒绝，未启动；未修改系统代理，保留连接问题未定位结论。

## 同日继续：完整 Chromium 用例与最终门禁

- 项目 `saas-forge-console-1788341677-66931-d7028e` 的全部扩展 Chromium 用例通过。新的公开 Client 正式创建成功；首个 GET 注入 401 后精确观察到 `GET 401 → Refresh 200 → GET 200`，重放使用不同的新凭据。实际创建另一个 Client 后丢失 201 响应，未自动重发；显式重试沿用原幂等键，真实 IAM 返回 `CLIENT_SECRET_ALREADY_REVEALED`，公共 GET 确认原 Client 可读。
- 同轮核心用户路径采用 390px 视口和键盘 Enter 激活；登录验证邮箱 Tab 到密码，首次改密、Membership、恢复、受保护导航及登出核对主标题焦点/读屏状态和无横向溢出。根错误安全重载及路由错误返回首页也通过。
- 请求故障分别覆盖不可重放登录的 401、503 Problem、畸形 200 和网络中断；只显示规范化代码，密码字段清空，不自动 Refresh/重放，原始响应标记不进入页面、存储或控制台。故障响应注入与真实正常链路明确分开。
- 初次最终 Maven 门禁被源码契约扫描阻止：新文件观察 Refresh 但未核对 `sessionSlot`。补充公开 Client、错误表单及默认入口快速回归的实际请求体槽位断言，保留原契约扫描与历史基线不变；聚焦 `BrowserSessionSlotContractTest` 两条通过。
- 项目 `saas-forge-console-1788342130-68909-4b922d` 的完整 `maven-verify` 已通过，包含 Console workspace、Chromium 视觉、两个独立生产构建和仓库质量门禁。该轮契约、Gateway、IAM、Tenant Access、Entitlement、Audit、Auth SDK 与 Spring Boot Starter 的当前 XML 报告合计 567 条，失败/错误/跳过均为 0；此统计不包含其他模块或前端用例。
- 同一脚本的 Chromium、WebKit、Chrome 真实产品测试均为 16 通过、0 失败、0 跳过，Chrome 兼容门禁通过。脚本最终因 WebKit 默认入口快速回归等待启动标记超时返回非零，不能将该次完整命令记为成功。

## 默认入口 WebKit 回归与最后补验

- 原失败夹具覆写 `navigator.locks.request` 来标记启动。在 WebKit 中，公开 `navigator.locks.query()` 已显示一个持锁者和一个等待者，但其中一页的标记仍为 false；配置 JSON 已完整读取，激活页面和另加启动屏障均未消除漏报。不能将该超时归因于产品尚未启动。
- 夹具改为通过公开锁队列建立并发屏障，保留实际 Refresh 观察、首个 HTTP 响应挂起及精确一次 Refresh 断言。未修改原生锁行为或读取 Runtime 私有字段。
- 修正后 WebKit 原生标签页 3/3 通过。临时将 Platform 默认 Realm 还原为空对象后，同一回归准确失败于 `2 !== 1`；随后立即恢复当前实现。Chromium 原生标签页 3/3、改动文件 lint/格式也通过。临时诊断已移除。
- 最后补验命令使用已通过 Maven 构建的相同产品工件：`scripts/verify-console-authentication-e2e.sh --product`，项目 `saas-forge-console-1788343918-76112-2ca09d`。该命令在镜像构建、引导和 Compose 健康检查通过后，因三个宿主 HTTPS 入口持续 `ECONNRESET` 而失败，未进入产品测试；本轮未重跑 Maven/workspace。失败项目的容器、数据卷和网络均已清理并读回确认为空。
- 本次宿主连接中断原因未确定，不把它归因于测试观察器或宣称已修复。保持正常 TLS 校验，不改系统代理或安装其他浏览器。此前项目 `saas-forge-console-1788342130-68909-4b922d` 的同一产品代码三渠道成功证据仍保留，但不得将最后补跑或完整脚本记为通过。
- 最后修正后的独立命令 `pnpm --dir consoles run test:browser:chrome` 与 `pnpm --dir consoles run test:browser:webkit` 均返回 0（通过固定 Node/pnpm 和 `PNPM_CONFIG_ENABLE_GLOBAL_VIRTUAL_STORE=false` 执行）。两个渠道分别为展示矩阵 6 通过/3 个视觉快照跳过、消费者矩阵 6 通过/1 个视觉快照跳过、原生标签页 3 通过/0 跳过。视觉快照由先前通过的 Chromium 门禁负责，没有跳过核心认证或能力回退用例。
- CI 工作流 YAML、Shell/JavaScript 语法及最终 diff 空白检查通过。截至本地提交前，改动未推送，GitHub Actions 尚未执行；#115 保持 OPEN。远端 `master` 为 `62ec07c`，本地已有 `d831a68`、`723823c`、`ee8d5b7` 三个认证前置提交；以当前 HEAD 创建测试分支并推送时会同时包含它们。

## 首轮远端 CI 失败与修复

提交 `3f912e4` 已推送到 `codex/issue-115-console-authentication-e2e`，触发 [Verify](https://github.com/crane199709/saas-forge/actions/runs/33620094798) 和 [五浏览器真实产品验收](https://github.com/crane199709/saas-forge/actions/runs/33620094928)。以下是已取得的失败证据，不把仍运行的任务推断为成功。

- Verify 的 Chrome、Edge、Firefox、WebKit 均在加载消费者测试时无法解析 `shared/api-client/.generated/index`；浏览器安装步骤最终成功，测试尚未进入行为断言。独立矩阵此前只有依赖和浏览器安装，缺少正式 Client 生成。工作流补上 JDK 17 与既有 `pnpm run generate:api`，不提交生成工件。
- 本地临时移开生成目录后，Chrome 消费者测试以相同导入错误失败；恢复目录并执行工作流新增的生成命令后，原消费者测试 6 通过、1 个非 Chromium 视觉快照按约定跳过。生成命令、工作流 YAML/步骤顺序和 Shell 语法检查通过。
- Tenant lifecycle E2E 在第 8/13 阶段第二次 Tenant 登录得到 `409 SESSION_SLOT_ALREADY_ACTIVE`。夹具要建立两个 Refresh Token Family，却复用了第一份已登录 Cookie。现保留第一份 Cookie 文件，用独立的新 Cookie 文件建立第二个会话；不修改服务端槽位校验，不登出或撤销第一个会话。全新 Compose 回归已通过原失败点，Tenant Access 撤销快照为 `SUSPENDED:COMPLETED:0:2:2:1`，IAM 为 `COMPLETED:0:2:2`，证明两个会话族及两个访问令牌均被撤销；其后完整 13/13 阶段、审计故障恢复和敏感明文扫描均通过，命令退出 0；隔离项目 `saas-forge-tenant-lifecycle-78993-82298` 的容器和数据卷已清理并读回为空。
- Verify 的 JDK 17、JDK 21、Nacos 最终通过。真实产品任务随后结束：Maven、容器引导、两个渠道的受信 TLS 就绪检查均通过，Chromium 16/16、0 跳过；Linux WebKit 产品测试失败，后续 Firefox/Chrome/Edge 产品渠道未执行。
- 首轮产品失败仅输出 runner 临时诊断路径，原始日志未上传且任务结束后不可获取，无法据此断定 WebKit 根因。新增 CLI 只输出 TAP 失败编号、已知测试源码位置、固定断言错误码和统计，不输出标题、原始错误、actual/expected 或任意堆栈。公开 CLI 回归先 RED 后 GREEN，验证敏感诊断值不出现在输出中；lint/格式及脚本语法通过。原始日志继续只留受限临时目录。
- 已定位的 Client 生成和会话夹具修复，以及 WebKit 定位所需的安全摘要，均需提交后复验；不能将新增诊断当作 WebKit 产品故障已修复。

提交 `82ad2e3` 已推送并触发 [第二轮 Verify](https://github.com/crane199709/saas-forge/actions/runs/33622146190) 和 [第二轮产品验收](https://github.com/crane199709/saas-forge/actions/runs/33622146203)。Chrome、Edge、Firefox、Linux WebKit 四个独立兼容任务已全部通过，证实正式 Client 生成步骤修复了原导入失败；第二轮 Verify 最终全部通过，包括生命周期 E2E、JDK 17/21 和 Nacos；独立产品任务仍在 Linux WebKit 失败；摘要定位到 `82ad2e3` 的 `console-authentication.test.mjs:127`，首次改密响应并非 204。该轮只有 6 条顶层测试被执行（5 通过、1 失败），后续嵌套会话用例尚未进入，不能归因于多标签页竞争。

## Linux WebKit 首次改密诊断

- 第二轮真实产品的 Maven、Chromium 16/16、两次受信 TLS 就绪均通过，失败发生在首次改密响应状态断言。当前摘要尚不含该 HTTP 状态或 Cookie 观察信息，因此根因仍未知。
- 补充失败专用的有限诊断：HTTP 状态、改密前浏览器是否存有 Platform Cookie、工具是否观察到请求 Cookie、请求密码是否匹配，以及四个已知 Problem 代码或 OTHER。所有密码、Cookie 值与原始响应均留在内存，不作为诊断输出；“工具未观察到 Cookie”不直接等同于服务端未收到 Cookie。
- 摘要现在只读取 TAP 的结构化字段，多行 error/actual 内容不能冒充失败编号或代码位置。公开 CLI 回归覆盖仿造多行响应和有限状态摘要，分别先 RED 后 GREEN；lint/格式通过。
- 各产品渠道仍使用独立数据卷，将 WebKit 提前以缩短失败定位等待，不跳过其他渠道或 Maven/workspace 门禁。上述诊断需推送后从下一轮 CI 获取证据，尚未宣称首次改密故障已修复。

提交 `449104e` 已触发 [第三轮 Verify](https://github.com/crane199709/saas-forge/actions/runs/33624326375) 与 [第三轮产品验收](https://github.com/crane199709/saas-forge/actions/runs/33624326355)。第三轮 Verify 全部通过；产品任务在 Linux WebKit 首次改密处失败，安全诊断为 `status=401 cookieStored=false cookieObserved=false requestMatches=true problem=PASSWORD_CHANGE_SESSION_INVALID`。已确认请求密码匹配，尚不能区分登录响应未设置 Cookie、旧响应清除 Cookie 或浏览器拒收。

新增观察逻辑的本地 WebKit 聚焦验证通过：项目 `saas-forge-console-1788348327-88682-8a43ec`，受信 TLS、首次改密及全部 16 条产品用例均通过，失败/跳过为 0；聚焦命令退出 0，仅代表该渠道，不包含 Maven/workspace 或其他渠道复验。

为区分上述原因，继续补充首次登录到改密间按响应顺序排列的 Cookie 元数据：固定 operation、HTTP 状态、set/clear/none/mixed 与安全属性是否符合约定；不输出 Cookie 值。公开摘要 CLI 回归先 RED 后 GREEN，覆盖允许字段与恶意多行内容隔离，12 条 Console 边界/诊断测试和相关 lint 通过；产品断言及 Cookie 安全设置保持不变。

提交 `26f95a8` 已推送；[第四轮 Verify](https://github.com/crane199709/saas-forge/actions/runs/33626041136) 全部通过；[第四轮产品验收](https://github.com/crane199709/saas-forge/actions/runs/33626041247) 的 Maven、构建、引导与 TLS 就绪均通过，Linux WebKit 再次在首次改密返回 401。响应顺序为 `refresh 401 clear → login 200 set → password-changes 401 clear`，三次 Cookie 均符合已检查的 Secure、HttpOnly、SameSite=Strict、Path=/、无 Domain 属性；浏览器在登录后未观察到 Platform Cookie，密码请求匹配。没有观察到登录后、改密前的清除响应。

CI 安装的是 libsoup `3.4.4-5ubuntu0.7`。[libsoup Cookie 接收逻辑](https://github.com/GNOME/libsoup/blob/3.4.4/libsoup/cookies/soup-cookie-jar.c) 通过基础域判断第三方 Cookie；[基础域实现](https://github.com/GNOME/libsoup/blob/master/libsoup/soup-tld.c) 对未识别顶级域返回空值，Cookie 接收逻辑随后比较完整主机名。这与 `.test` 下两个不同子域被当作第三方、macOS WebKit 成功而 Linux 失败的现象一致，但尚未完成更换根域的对照实验，不能宣称最终根因或修复已证实。

建议的下一步是仅在隔离 CI 中以 `platform.saas.forge.example.com`、`console.saas.forge.example.com`、`api.saas.forge.example.com` 做对照，临时 hosts 仍仅指向 127.0.0.1，TLS 与 Cookie 安全属性不变，本机现有配置不变。Issue #115 明确写死 `.saas.forge.test`，因此该实验和后续验收域名调整需要用户确认；尚未实施，远端 Issue 未修改。

## 经确认的 CI 根域对照

用户已确认仅在 CI 对照使用 `platform.saas.forge.example.com`、`console.saas.forge.example.com` 与 `api.saas.forge.example.com`。`SF_ACCEPTANCE_ROOT_DOMAIN` 默认仍为 `saas.forge.test`；CI 明确设置为 `saas.forge.example.com`，同步临时证书 SAN/CA 约束、hosts、Gateway/IAM 根域、三个入口和浏览器夹具。代理只接受配置根域下的三个 Host，根域只允许上述两个值；HttpOnly/Secure/SameSite、Origin/Fetch Metadata 和 TLS 校验保持原样。测试账号邮箱与 JWT issuer 作为固定身份数据保持原值。

新增 Linux libsoup 公共函数对照观察，只输出两组公开域名是否具有相同基础域，不输出 Cookie 或凭据。本地 12 条边界/诊断测试、相关 ESLint、JS/Shell 语法、YAML 和 diff 检查通过；使用占位配置验证两个根域均准确传入 Gateway、IAM 和三个入口。尚待本次 CI 的真实产品结果；未修改本机 hosts、信任库或浏览器安装，未修改远端 Issue 验收条款。

提交 `1efe67b` 的 [Verify](https://github.com/crane199709/saas-forge/actions/runs/33635862151) 全部通过。[产品对照](https://github.com/crane199709/saas-forge/actions/runs/33635862213) 直接输出 `saas.forge.test sharedBase=false`、`saas.forge.example.com sharedBase=true`。WebKit 首次改密及其后的双槽位、Membership、Tenant Switch、多标签页、Lease 回退、路由错误与正式 Client 场景已进入并通过；共执行 16 条，13 通过、3 失败（两个叶子断言失败及其父测试）。剩余断言位于存储安全检查和请求错误检查，二者的会话键白名单正则仍写死 `.saas.forge.test`。本次只将这两处改为配置根域下的精确键名比较，继续限制 PLATFORM/TENANT、generation/logoutPending 以及原有值校验；相关 lint/格式通过，需下一轮 CI 复验。

提交 `9600669` 的[产品验收](https://github.com/crane199709/saas-forge/actions/runs/33637759472)中，WebKit、Chromium 均为 16/16、0 失败/跳过，证明根域对照和存储检查已通过。Firefox 随后在初始导航阶段 6/6 失败，尚未进入认证断言，Chrome/Edge 产品渠道未执行。[Verify](https://github.com/crane199709/saas-forge/actions/runs/33637759471) 的 Nacos 初始化首次失败于 `nacos-init` 退出 1，同一提交单独重跑该任务后 4m23s 通过，其他门禁均通过；首次初始化失败的具体原因尚未确定，未修改 Nacos 代码或配置。

检查实际缓存的 Firefox 1538 `playwright.cfg` 及[官方源码](https://github.com/microsoft/playwright/blob/main/browser_patches/firefox/preferences/playwright.cfg)，确认策略通过 `PLAYWRIGHT_FIREFOX_POLICIES_JSON` 指定，而原工作流仅写普通 Firefox 的 distribution 目录。现将相同证书安装策略保存在本次临时 TLS 目录并导出该环境变量；不设置忽略 TLS、不调整浏览器安全边界。新增预检用本次证书在回环随机端口提供临时 HTTPS，并由每个所需浏览器正常验证三个受控 Host，提前发现信任问题；正式产品仍必须通过 443 与全部真实服务。CI 优先运行 Firefox，但保留所有渠道及原断言。上述修正尚待 CI 复验，未在本机安装或运行 Firefox/Edge。

## Firefox 安全负向的观察边界

提交 `647191d` 的 [Verify](https://github.com/crane199709/saas-forge/actions/runs/33640770715) 首次运行全部通过。[产品验收](https://github.com/crane199709/saas-forge/actions/runs/33640770708) 五渠道临时 TLS 导航均通过，Firefox 正式产品 15/16、0 跳过；首次改密、双槽位、多标签页、Lease 回退等产品路径已通过。唯一失败位于 `console-authentication.test.mjs:920`：非法 CSRF 请求的 fetch 已被拒绝，但观察器未收到匹配的 CORS 控制台文本。其前的正常请求 204 和浏览器管理的 Origin / same-site 断言已通过；尚不能凭该日志判断控制台消息缺失的具体原因。

修正只涉及验收取证：复用现有随机 `acceptanceProbe`，由隔离 TLS 代理记录原样转发的上游响应是否为 403、是否存在 Access-Control-Allow-Origin，不输出响应体、头值或凭据。两个 CORS 负向请求必须同时满足浏览器 fetch 拒绝、真实 Gateway 403、无允许头、前后健康请求 204 与最终无 Cookie；不依赖各引擎的控制台文案。Intent 403、opaque cross-site 和全部安全策略保持原样。相关 ESLint、格式、语法与 diff 检查通过，真实五渠道仍待下一轮 CI；未本地执行 Firefox/Edge。

## 五渠道产品通过，兼容汇总待定位

提交 `cef0569` 的 [Verify](https://github.com/crane199709/saas-forge/actions/runs/33643294888) 全部通过。[完整产品任务](https://github.com/crane199709/saas-forge/actions/runs/33643294905) 按 Firefox → WebKit → Chromium → Chrome → Edge 执行，每个渠道均为 16 通过、0 失败、0 跳过，各自重新创建数据卷、引导账户、启动服务并验证受信 TLS。CORS 负向修正已由五渠道真实 Gateway 403 / 无允许头及浏览器拒绝证据通过验证。

产品之后的 `console-browser-compatibility` 返回非零。该阶段原先只保留 runner 临时原始日志，未输出安全摘要，任务结束后无法取得具体失败；不能用同提交独立兼容任务均通过推断汇总成功。现将相同四个兼容命令分别记账，并扩展摘要支持 Vitest / Node spec reporter 的固定错误码和已知测试位置；不输出测试标题、断言值、任意堆栈或响应。公开 CLI 回归先 RED 后 GREEN，全部 13 条边界/诊断测试、lint、格式、Shell 语法与 diff 检查通过。未调整产品行为或测试时序，待 CI 给出剩余失败证据。

## 兼容命令的 Corepack 工作目录

提交 `7b50b20` 的 [Verify](https://github.com/crane199709/saas-forge/actions/runs/33647127433) 全部通过；[完整产品](https://github.com/crane199709/saas-forge/actions/runs/33647127405) 再次五渠道各 16/16、0 失败/跳过，但首个 `console-browser-chrome` 在测试开始前失败，原摘要没有识别该启动错误码。

已有 `scripts/verify-frontend-workspace.sh` 明确在 `consoles` 内启动 pnpm；聚合末尾此前从仓库根目录使用 `pnpm --dir`，Corepack 会先按 cwd 解析版本。[Corepack 官方说明](https://github.com/nodejs/corepack#known-good-releases)明确，无 packageManager 的目录使用 Known Good Release。在临时 Corepack home 中仅引用已缓存的 pnpm 10.33.2 / 11.22.0、禁用网络并将默认版本设为 10.33.2：根目录 `corepack pnpm --dir consoles --version` 输出 10.33.2，而进入 `consoles` 后为 11.22.0；同一 `test:boundaries` 命令前者报 `ERR_PNPM_VERIFY_DEPS_BEFORE_RUN`、退出 1，后者 13/13、退出 0。临时目录已清理，未安装依赖或修改全局 Corepack 配置。

现将末尾全部兼容命令放入 `consoles` 工作目录的子 Shell，与 Maven 既有方式一致；仍执行相同四个渠道及原断言。摘要补充刚复现的固定错误码，公开 CLI 回归先 RED 后 GREEN，13 条测试、lint、格式、Shell 语法与 diff 检查通过。该本地复现与 CI 测试启动前失败一致，但远端原始错误内容不可取回，仍需新一轮完整 CI 确认修正。

## 2026-09-03 最终 CI 读回

代码提交 `5023f2498170e7a55de606d5f85561da7b24338d` 的完整产品任务用时 29m59s，结论为 success。日志确认每次渠道切换均清理数据卷并重新引导账户，Firefox 153.0、WebKit 26.5、Chromium 151.0.7922.34、Chrome 152.0.7977.75、Edge 152.0.4191.53 各 16/16、0 失败/跳过；之后 Chrome、Edge、Firefox、WebKit 四个兼容命令依次通过，最终输出完整 Maven/workspace、生产构建、Fresh Compose 与浏览器门禁全部通过。相同提交的 Verify 也为 success，包括生命周期 E2E、Nacos、JDK 17/21 与四个独立兼容任务。

工作目录是最后一轮唯一改变执行行为的修正；修正后兼容命令通过，与临时 Corepack 环境中旧方式失败、新方式成功的对照一致。原 CI 启动错误的完整文本仍不可取回，不补造历史诊断。所有渠道持续使用正常 TLS、安全 Cookie 与真实受控 Origin；本机未安装或运行 Firefox/Edge。

## 验收证据与剩余事项

以下勾选表示已在批准的 CI 示例根域下取得完整产品证据；不表示已改写或关闭远端 Issue，也不将其自动等同于原文写死 `.saas.forge.test` 的域名条款。

- [x] 全新 Compose 数据卷、真实服务及三 Origin 的受信 TLS 产品入口。
- [x] Platform / Tenant 同时登录、独立 Refresh Cookie、内存 Access Token 与独立刷新/登出。
- [x] 首次改密后重新登录、单/多 Membership、冷启动恢复与受保护导航。
- [x] Tenant Switch 的 `204 → Refresh`、恢复失败、不回滚与品牌/导航一致性。
- [x] 同 Origin 竞争、迟到消息、单调代次、`logoutPending` 与 IAM Lease 回退。
- [x] 真实浏览器安全负向，以及 Gateway/IAM 拒绝分支的聚焦测试。
- [x] 存储、脚本可读 Cookie、跨标签页消息与生产日志敏感信息检查。
- [x] 单次 `401` 刷新/重放、不可重放变更、稳定幂等句柄、Retry-After、畸形响应及网络不可判定。
- [x] 根/路由/请求错误分层、生产脱敏、键盘、焦点、读屏与窄屏。
- [x] 三引擎核心认证与 Chrome/Edge 发布渠道；Chromium 视觉快照的本地证据见前文，CI 未将非 Chromium 视觉跳过等同于核心行为通过。
- [x] 两个生产构建、workspace、Maven/契约/服务及 Fresh Compose 聚合验证全部通过。
- [ ] 确认是否将“本地 `.saas.forge.test`、CI `.saas.forge.example.com`，均使用受信 TLS 和同等受控 Origin/Cookie 边界”纳入 #115 正式验收条款。
- [ ] 完成正式条款与前置 Issue 的最终核对后，再处理 #115 / #108 和 MVP 完成状态。
