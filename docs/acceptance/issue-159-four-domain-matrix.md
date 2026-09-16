# 四域跨浏览器聚合验收（Issue #159）

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

> 当前阶段范围已由 [ADR 0046](../adr/0046-development-supports-chrome-and-jdk17.md) 调整为桌面 Chrome 当前稳定版与 JDK 17；Chromium 保留日常功能与视觉测试。本文旧矩阵的执行结果属于历史证据，不作为当前多浏览器或 JDK 21 要求。现行复现入口见 [本地验证说明](../local-verification.md)。

## 入口与边界

父规格为 #155。开发与 Fresh Compose 共用 `static-remote.test.mjs`、`browser-session-security.mjs` 和 `browser-api-security.mjs`；成功路径使用真实 Console、受信 TLS Edge、Gateway/真实服务及版本化 Remote。此验收不交付 Manifest、业务 Remote、生产/CDN 或完整升级回退治理，不修改或关闭父 Issue。

开发环境先按 `docs/local-static-remote-development.md` 启动四域与两个 Vite Console。提供具有 Platform Role、正式密码和有效 Tenant Membership 的同一验收账号；凭据仅通过受限文件读取。新观测模块要求开发 Edge 加载当前 Compose 配置，不能继续使用修改前的进程。

```bash
export SF_SESSION_EMAIL_FILE=/absolute/path/to/email
export SF_SESSION_PASSWORD_FILE=/absolute/path/to/current-password
export SF_BRAND_EVIDENCE_DIRECTORY="$PWD/.scratch/issue-159/development"
# 默认查找当前 Compose 项目的 local-https-edge；非默认项目设置 COMPOSE_PROJECT_NAME。
# 也可指定已核实属于本次开发项目的容器 ID：SF_SECURITY_EDGE_CONTAINER。
bash scripts/verify-console-authentication-e2e.sh --development
```

此入口只运行 Chrome，先对四域执行正常 TLS 导航；缺少浏览器、信任或服务记录 `blocked`，后续阶段记录 `not-run`。测试实际失败记录 `failed`。只有该环境 Chrome 全部阶段通过才返回 0。开发保留 Vite 模块与正式域名 WSS connected 证据；不清理开发数据库或创建测试 Tenant。

Fresh Compose 继续使用原入口。443 必须空闲；不能覆盖开发 Edge，先通过其受控生命周期释放端口，验收退出后再恢复开发入口。

```bash
export SF_ACCEPTANCE_TLS_CERT=/absolute/path/to/four-domain-server.pem
export SF_ACCEPTANCE_TLS_KEY=/absolute/path/to/server.key
export SF_BRAND_EVIDENCE_DIRECTORY="$PWD/.scratch/issue-159/fresh-compose"
bash scripts/verify-console-authentication-e2e.sh --preflight
bash scripts/verify-console-authentication-e2e.sh
```

`--product` 仅复用已有构建、重跑产品及兼容门禁，不代表本次执行了 Maven/workspace；产品渠道固定为 Chrome，无需设置 `SF_PRODUCT_CHANNEL`（旧变量仅接受 `chrome`，其他值拒绝执行）。Chrome 使用独立浏览器上下文与全新随机项目数据卷。清理只使用本次随机项目名；清理失败使整轮失败。Remote 制品仍由唯一构建目录及 `remote-static.mjs` 交付；两个环境都从浏览器下载资源并对照冻结 SHA-256 清单。

## 证据与安全

- `development-matrix.json`：开发渠道、版本、预检、阶段结果与阻塞分类。
- `acceptance-run.json`：Fresh 执行的提交、工作区修改标志、范围与阶段状态；必须结合 scope 判断，不能把 `--product` 当完整验收。
- `static-remote-<channel>.json`：模块执行、CSS 生效、图片解码、资源请求及无凭据/CORS 元数据。
- `static-remote-policy-<channel>.json`：冻结制品 SHA-256、版本重复读取、真实 404、非法来源浏览器拒绝与对应 Edge 响应。
- `session-security-<channel>/browser-sessions.json`：双槽位流程、Cookie 属性与范围、32 个探针、拒绝后 Cookie 不变及两侧真实恢复、开发 HMR。

这些证据名称对应的是本机临时位置：`.scratch/issue-159/**` 与 `/private/tmp/sf-159-*.log`。`.scratch/` 是仓库未跟踪的本地临时目录（不受 Git 跟踪），上述运行目录与日志现已不存在，其他读者无法从仓库复现；本节及「本次完成记录」中的相关引用只作为历史事实保留。可复现的现行入口为 `bash scripts/verify-console-authentication-e2e.sh`（见 [本地验证说明](../local-verification.md)），证据目录由调用者通过 `SF_BRAND_EVIDENCE_DIRECTORY` 自行指定，不属于仓库制品。

Chrome 使用 CDP ExtraInfo，CORS 隐藏的预检/拒绝由同一真实 TLS Edge 的随机关联探针日志补充。Edge 不修改请求或放宽安全规则；日志不包含 Cookie/Token 值、密码或正文。服务端不变必须由每个探针后的双 Console 实际恢复共同证明，不能仅凭 fetch 抛错或 Cookie 字节未变。

受限 `.log` 文件可能包含原始测试诊断，不直接公开。CI 使用 Chrome 产品工作流，四域都纳入证书 SAN 和 hosts；延续此前已批准的 Linux `saas.forge.example.com` 对照根域及相同主机推导策略。工作流以 `always()` 上传白名单 JSON，排除原始日志、凭据与截图。远端验收需记录对应提交的 workflow URL 和 artifact，不能引用旧运行替代。

## 父规格验收映射

| #155 验收项 | 对应证据/检查 |
| --- | --- |
| 四域受信 HTTPS | 两套环境分别预检及真实页面；CI SAN/hosts 四域 |
| 开发 HMR、E2E 构建、同 Remote | 会话证据 hmr；静态构建入口；浏览器 SHA-256 对照 |
| 双 Console 登录/恢复/独立登出 | browser-sessions actions 与每次实际刷新 |
| Cookie 安全属性与 API 主机范围 | Set-Cookie 属性、实际 Console/Remote 请求无 API Cookie |
| API 精确 CORS，Remote 不获许可 | 两侧预检头集合、实际响应、Remote/非法/null 探针 |
| CSRF 与槽位拒绝、状态不变 | 32 个探针及拒绝后双侧恢复 |
| ES Module/CSS/图片实际执行呈现 | static-remote rendering 与真实资源请求 |
| Remote 无凭据及精确 CORS | 非敏感 Cookie 夹具、网络头、关联负向 Edge 记录 |
| 固定双版本与真实 404 | policy versions、artifactHashes、missing |
| 本地与 CI Chrome 验收 | 分环境结果、对应远端运行和上传 JSON |
| Fresh 状态隔离与限项目清理 | 原随机 Compose 项目/全新卷检查、reset/cleanup 结果 |
| 文档与 MVP 状态 | 本文；全部证据成立前保留 MVP 未勾选 |

## 本次完成记录

本地验收冻结提交为 `baf67f5e6f3898caef931c94958410bb9370a327`。其后的 `562c72b` 仅增加 Maven 失败脱敏摘要，`acc9ea8` 仅隔离固定开发域单元测试夹具的 CI 根域；均未改变产品路径或浏览器验收断言。

| 环境 | Chromium 151.0.7922.34 | WebKit 26.5 | Chrome 153.0.8010.36 | 证据 |
| --- | --- | --- | --- | --- |
| 开发四域 | 通过 | 通过 | 通过 | `.scratch/issue-159/development-baf67f5/development-matrix.json`（本机临时证据，已不存在，不可复现） |
| Fresh Compose | 33/33，零跳过 | 33/33，零跳过 | 33/33，零跳过 | `.scratch/issue-159/fresh-baf67f5/acceptance-run.json`（本机临时证据，已不存在，不可复现） |

两套环境各浏览器均完成 32 个安全探针、拒绝后双侧恢复及独立登出，`errors=[]`。六组静态证据均包含模块执行、CSS 生效、图片解码、无凭据请求、双版本冻结哈希、真实 404，以及非法来源的浏览器拒绝和关联 Edge 响应。开发三个浏览器均记录两个正式 Console 域的 WSS HMR。完整本地脚本的脱敏 TAP 汇总保留于 `/private/tmp/sf-159-fresh-baf67f5.log`（本机临时证据，已不存在，不可复现）。

Fresh 清单记录 `scope=--full`、`dirty=false`、`status=passed`；Maven/workspace、生产构建、服务镜像、浏览器产品与兼容门禁通过。随机项目 `saas-forge-console-1788968761-43555-9f1abf` 的容器和数据卷已确认清理。开发 Nacos、后端与 Edge 已恢复；Gateway 回到 `LOCAL / READY`，正常 TLS 下两个 Console 返回 200，API/Remote 根路径返回 404。icube 容器保持运行。

失败轮次不计入通过结果：早期运行暴露 WebKit 网络事件差异、默认 favicon 探测与资源边界问题，修正后重验；Docker OOM 与宿主 443 转发异常经用户授权重启 Docker Desktop 后恢复。恢复开发 Edge 时再次遇到连接重置，仅重启该 Edge 后恢复。远端 `34371367288` 两次在 Maven 阶段失败；随后以 CI 环境变量在本地复现固定 `.test` 夹具受对照根域污染，并在 `acc9ea8` 修复。修复后的 CI 配置全量 workspace 验证通过。

`acc9ea8` 的远端产品运行通过 Maven 与 Compose 启动后，在四域 TLS 就绪阶段失败。补充脱敏诊断的 `34377861155` 确认 Platform、Console、API 均为 200，只有 Remote 为 503。受限构建产生的 Remote 文件为 `0600`，固定 `node` UID 无法读取 Linux 构建者的文件；容器原生文件系统对照验证为 `EACCES` / 构建者可读。`da4f8ec7e4339b866ef9ede267a9af16017aed0e` 将 TLS Edge 与两个 Console 统一为构建者 UID/GID，保留只读挂载与文件权限。

该配置修复后的本地 Fresh 产品回归位于 `.scratch/issue-159/fresh-da4f8ec-product`（本机临时证据，已不存在，不可复现）：三个浏览器各 33/33、零跳过，兼容门禁通过，随机项目 `saas-forge-console-1788972083-60958-519dd5` 的容器和卷已清理。此轮 `scope=--product`，不声称重新执行 Maven；`dirty=true` 对应仅有的验收文档草稿，代码已提交。脱敏 TAP 汇总为 `/private/tmp/sf-159-fresh-da4f8ec-product.log`（本机临时证据，已不存在，不可复现）。

`da4f8ec` 的远端运行确认 Remote 恢复，Firefox 与 WebKit 产品测试通过；Chromium 的顶层 `data:` null Origin 探针未到达 Edge，整轮仍为失败。`b9afa302e037df1b71895d4c11a17945ea3ff0ac` 改用受信页面创建的 `sandbox="allow-scripts"` iframe，并断言浏览器 Origin 为 `null`；未手工设置 Origin 或关闭浏览器安全检查。拒绝、Cookie 不变与双侧恢复仍由真实网络和关联 Edge 记录证明。

此修复的开发静态 Remote 三浏览器回归均通过（`.scratch/issue-159/opaque-frame-{chromium,webkit,chrome}`，本机临时证据，已不存在，不可复现）；最新 Fresh 产品回归为 `.scratch/issue-159/fresh-b9afa30-product`（本机临时证据，已不存在，不可复现），三个浏览器各 33/33、零跳过、32 个安全探针、`errors=[]`，兼容门禁通过。该轮同样为 `--product`，仅验收文档未提交；随机项目 `saas-forge-console-1788973726-67311-b51dba` 的容器和卷已清理。脱敏汇总为 `/private/tmp/sf-159-fresh-b9afa30-product.log`（本机临时证据，已不存在，不可复现）。

另行记录的失败：[Verify 34381031337](https://github.com/crane199709/saas-forge/actions/runs/34381031337) 的 Tenant lifecycle fresh-volume E2E 两次在 Redis 停启探针失败，`POST /api/v1/platform/tenants` 预期 503、实际 201（脚本第 792 行）。本任务未修改该脚本或后端业务，但尚未定位失败原因，不能据此断言因果关系。该项不属于 #159 的四域产品门禁；不得将本记录表述为全部 CI、整个第 1 阶段或 MVP 发布门禁通过。

最终源码 `b9afa302e037df1b71895d4c11a17945ea3ff0ac` 的 [五浏览器产品 CI 34381031323](https://github.com/crane199709/saas-forge/actions/runs/34381031323) 已通过；[脱敏 artifact](https://github.com/crane199709/saas-forge/actions/runs/34381031323/artifacts/10117219594) 为 `four-domain-browser-evidence-b9afa302e037df1b71895d4c11a17945ea3ff0ac`，本地核对副本为 `.scratch/issue-159/ci-b9afa30-passed`（本机临时证据，已不存在，不可复现）。

| CI 浏览器 | 版本 | 产品测试 | 安全探针 | 静态 Remote |
| --- | --- | --- | --- | --- |
| Chromium | 151.0.7922.34 | 33/33，零跳过 | 32，零未预期错误 | 通过 |
| WebKit | 26.5 | 33/33，零跳过 | 32，零未预期错误 | 通过 |
| Chrome | 153.0.8010.36 | 33/33，零跳过 | 32，零未预期错误 | 通过 |
| Firefox | 153.0 | 33/33，零跳过 | 32，零未预期错误 | 通过 |
| Microsoft Edge | 152.0.4191.66 | 33/33，零跳过 | 32，零未预期错误 | 通过 |

CI 清单为 `scope=--full`、`dirty=false`、`status=passed`，包含 Maven/workspace、构建、五个产品渠道、四个兼容渠道和状态重置。最终成功状态在限定随机项目清理成功后写入。后续完成记录提交仅修改文档，不能把上述源码验收结果误称为文档提交自身的 CI 已通过。

2026-09-10 补跑最终源码的完整开发矩阵：`.scratch/issue-159/development-b9afa30/development-matrix.json`（本机临时证据，已不存在，不可复现）三渠道均通过，各有 32 个安全探针、`errors=[]`，两个 Console 正式域名 WSS HMR 均已记录。开发 Gateway 恢复为 `LOCAL / READY`，四域正常 TLS 已核实；本地账号凭据文件已删除。结合上述完整 CI 与本地 Fresh 回归，#159 的四域单项验收完成，MVP 仅勾选对应条目；Manifest、业务 Remote、父 #155 及整个第 1 阶段不由本次记录宣告完成。
