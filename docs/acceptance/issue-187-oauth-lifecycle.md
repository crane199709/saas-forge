# #187 OAuth Client 真实服务消费验收

本切片验证平台机制，不代表生产 Runtime 业务，也不替代父 #183/#189 的同轮聚合。
沿用 #183 已确认的真实桌面 Chrome、JDK 17、受信 HTTPS、独立 Fresh Compose 和测试 Registry overlay。

## 可重复入口

环境方按[独立环境交接](independent-verification.md)创建新的项目、网络和数据卷，并使用
`-Pplatform-mechanism-acceptance` 构建 Gateway 与非生产 Receiver。启用统一 Console，完成部署必需的
平台管理员和保留服务身份引导；不预建 OAuth 业务资源。使用专属端口连接前端原生 dev 入口，
不得接管开发者进程。准备后生成 Fresh handoff 并执行后端探针。

前端仓库运行 `node scripts/verify-oauth-lifecycle.mjs <新输出目录>`。
`SF_OAUTH_CONFIG` 指向开发者自行维护的 0600 JSON 文件，不提交个人配置模板：

| 字段 | 内容 |
| --- | --- |
| `handoff` | 本轮 Fresh handoff 文件 |
| `adminEmailFile`、`initialPasswordFile`、`passwordFile` | 初始管理员邮箱、初始密码、经 Console 设置的新密码；均为 0600 独立文件 |
| `gateway` | 本轮 Gateway 的随机回环 HTTP Origin，供产品外独立服务消费与重放探针 |
| `controlDirectory` | 本轮专用 0700 时间注入交接目录，不与其他运行共享 |
| `httpsPort` | 可选的独立 HTTPS Edge 回环端口；Chrome 使用进程内域名映射并保留证书验证 |

环境方另行运行：

```sh
node scripts/oauth-overlap-time-control.mjs <handoff.json> <controlDirectory>
```

前端不启动、停止 Docker，不执行 SQL。环境控制器只处理本轮 Client 的一个旧 Secret 的
`valid_until`：核对项目、容器创建时间、专属数据库卷、Client 创建时间和带 runId 的名称、
RUNTIME_SERVICE/ACTIVE、唯一旧 Secret、原始时间和影响行数。注入和恢复均使用原值条件保护；
进程正常退出、失败和租约到期均尝试恢复。强制终止或数据库不可用时，保留本地恢复元数据，
未确认恢复不得判为通过。时间恢复元数据仅包含非敏感资源标识与时间，不包含 Secret 摘要或明文；
同目录的原始服务日志保持 0600，仅用于本机敏感材料扫描，不作为交付物。

## 场景与判定

- 新浏览器按中文偏好完成初始管理员改密、登录、平台上下文选择，再从真实 Console 创建 Client。
- 同一创建请求的独立幂等重放必须返回 `409 CLIENT_SECRET_ALREADY_REVEALED`，不能返回旧 Secret。
  服务凭据仅留在测试进程内存中，不保存 storageState、HAR、trace、录像或含 Secret 的截图。
- 同轮旧、新凭据实际换取 Token，并经 Gateway → Nacos → Receiver/Starter → Redis 验证消费。
  Scope 不足必须是 `403 ACCESS_TOKEN_SCOPE_INSUFFICIENT`，网络错误或 404 不计通过。
- 第二个独立 Console 会话正常发起常规轮换，暂扣真实请求至第一个轮换提交成功后再放行。
  不伪造权威状态或响应；必须观察 `409 CLIENT_SECRET_ROTATION_OVERLAP_ACTIVE`。
  拒绝后截止时间不变、新旧凭据仍可消费；刷新详情时轮换按钮禁用。
- 轮换成功响应的权威 `updatedAt` 与重叠截止时间相差 86,400 秒。随后实施受保护的到期状态注入，旧凭据必须
  `401 CLIENT_CREDENTIALS_INVALID`，新凭据仍可消费；恢复原时间后再由 Console 吊销。
  **没有实际等待 24 小时，也不修改宿主时钟**；精确边界由既有固定 Clock 与 PostgreSQL 测试承担。
- 吊销等待服务端 204 后验证：不能新签发，且已明确尚未到期的既有 Token 被真实接收路径拒绝。
- Secret 关闭、展示期间刷新/离开、前进后退及跨标签退出后不可重读；创建和轮换展示时立即检查
  localStorage/sessionStorage、IndexedDB、Cache Storage 和浏览器日志，不能等退出清空后才检查。
- 未知 Console 错误、pageerror 和正常路径 HTTP 失败阻断通过。预期 409 按上下文、路径、
  method、场景、状态码/错误码与时间窗口关联，不全局忽略状态码。

`oauth.json` 记录 runId、handoff SHA-256、两仓来源、Chrome、逐场景结果与请求 Trace/时间。
`injections.json` 记录同轮资源散列别名、原时间/注入时间和恢复结果。控制器在全部场景结束后
只读采集本轮六个服务的日志，前端在内存中核对已知敏感材料（含 Basic 编码），原始日志不提交。失败的 `diagnostic.txt`
仅作本地诊断，不作为可提交证据。长期证据由环境方审核脱敏后转存到 `docs/acceptance/`；
`.scratch/` 不作为交付依据。

## 本范围产品修复

真实 Chrome 竞态场景暴露：服务端明确返回重叠窗口拒绝后，Console 仍保留未知结果锁。
前端现在仅对 `409 CLIENT_SECRET_ROTATION_OVERLAP_ACTIVE` 清除本次常规轮换的 pending，
未知 409、网络失败、取消与过期会话仍保留原保护。详情重新读取成功后保留中英重叠提示；
若后续读取失败，优先呈现该失败，不用重叠提示覆盖。

真实页面无障碍检查还发现恢复记录表表头对比度不足、横向滚动区域没有键盘焦点入口。
表头改用现有主题的常规文字色，滚动区域设置 tabindex=0；未改变恢复权限或业务行为。

## 本轮状态

2026-09-29 的最终独立 Fresh 轮次 `05a5f07e-af4a-4b19-bf90-10d249cb2dd9` 通过全部 12 个真实场景。
桌面 Google Chrome 为 `154.0.8037.58`，后端与 Receiver 均为 JDK 17。
后端基线 `bde45e24e666f0e906bf5415634b2a4c31e06b03`，前端基线
`54981ce25d60c7dc2d1926842d48870baf62972d`，运行时两仓 dirty=true；本次不修改后端运行时代码。
前端运行源码摘要、锁文件摘要、正式 Client 版本与来源、两侧验收脚本摘要均保留在证据中。
前端交付提交为 `d89bd87b74758245dfa1ee9225564554464db974`；提交钩子通过，提交后的源码与锁文件摘要
重新核对，与真实验收记录完全一致。
镜像标签是本轮构建输入记录，不是独立签名供应链证明。

最终证据位于 [issue-187-evidence](issue-187-evidence/correlation.json)，
[SHA256SUMS](issue-187-evidence/SHA256SUMS) 覆盖该目录各 JSON：

| Issue 验收范围 | 本轮直接证据 |
| --- | --- |
| 1. Console 创建、一次性 Secret 与清除 | `oauth.json` 中创建重放 409、关闭/刷新/离开/历史导航/跨标签退出检查；展示期间存储扫描 |
| 2. 真实 Token 与 Scope | `oauth.json` 中 receiver 成功消费与 Scope 不足 403，`receiver.json` 记录非生产接收端来源 |
| 3. 常规轮换与重叠拒绝 | 两个正常 Console 会话、实际延迟请求；409 精确错误码，中英提示、截止不变及旧新凭据仍可消费 |
| 4. 到期 | `injections.json` 与 Client 散列别名一致，注入和恢复各 1 行；旧拒绝新成功，没有实际等待 24 小时 |
| 5. 吊销 | Console 返回 204 后，新签发与明确尚未到期的旧 Token 均被拒绝 |
| 6. 关联与凭据 | `correlation.json` 验证同轮、同交接摘要和同 Client；浏览器及六服务日志无已知敏感材料 |
| 7. Fresh 前置 | `handoff.json` 的新项目/卷/网络/镜像，`backend.json` 的 HTTPS/Origin 探针，首次改密与平台上下文真实页面流程 |
| 8. 来源与错误 | `oauth.json` 保留来源、时间、逐请求状态和预期错误；无未知 Console/pageerror 或非预期业务 HTTP 失败 |
| 9. 相关回归 | `verification.json` 的契约/安全/边界测试、前端回归与真实中英提示、键盘创建/关闭、产品 #app 无障碍检查 |

轮换权威更新时间 `2026-09-29T11:21:29.168Z`，重叠截止为次日同一时刻，差值恰为 86,400 秒。
到期注入恢复确认后才执行吊销；成功不依赖诊断轮次的数据或历史 #208 的结果。

| 验证 | 结果与边界 |
| --- | --- |
| 后端完整 Maven verify，含 platform-mechanism-acceptance | 726 项通过，0 失败/错误/跳过；含已有固定 Clock、PostgreSQL、契约和安全测试 |
| 后端 Node 工具测试 | 62 项通过 |
| 前端类型、Lint、双语资源、生产构建 | 通过；中英 733 个键 |
| 前端 Node 测试 | 223 项通过 |
| Chromium Mock HTTP UI | 13 项通过；与真实后端验证分开计数 |
| 最终真实 Chrome + Fresh 后端 | 12 项通过；产品 #app 的 WCAG 2 A/AA、2.1 AA 自动检查零违规，明确排除开发工具浮层 |
| Standards / Spec 两路复审 | 无剩余发现 |
| CI、其他浏览器、JDK 21、生产 Runtime | 未执行，不据此声明通过 |

初始真实竞态验证为失败（明确 409 被显示为未知结果锁），修复后在上述全新轮次通过。
驱动定位/导航时序、环境准备及无障碍检查中出现的失败记录在
[diagnostic-history.json](issue-187-evidence/diagnostic-history.json)，不拼入最终验收。
本地受限文件、原始服务日志、凭据及含敏感数据的浏览器状态均不提交。
验收完成后已停止本任务专属 Compose 环境、原生 Vite 和控制器，保留数据卷；未接管其他开发环境。
本记录只覆盖 #187 平台机制切片，不勾选父 #183/#189 的完整验收，也不自动关闭 GitHub Issue。
