# #185 Token、Tenant 越权和 Redis 专项验收

本票沿用 #183 已确认的真实 Chrome、JDK 17、受信 HTTPS 与隔离 Fresh Compose 边界。
旧 #208 的 `unauthorized-platform-context` 只证明平台权限拒绝，不能代替其他 Tenant 的越权拒绝。
本轮不改产品授权、Refresh 重放或撤销语义。

## 可重复入口

后端先按 [环境交接](independent-verification.md) 准备新的独立项目，启用统一 Console，配置真实邮件与既有
`platform-mechanism-receiver` 非生产接收端。所有服务使用当前代码构建，环境方保留 preparation/handoff。
Gateway 与 Receiver 必须以 `-Pplatform-mechanism-acceptance` 构建，使两者的路由目录包含测试 overlay；
仅启动 Receiver 容器不足以建立 Gateway 路由。入口先验证该路由返回明确的 401，再消耗一次性业务前置。
前端仍以自己的原生 dev 入口启动。不得复用业务数据卷、修改宿主信任或接管开发者端口。

前端仓库运行 `node scripts/verify-token-security.mjs <新的输出目录>`。
`SF_SECURITY_CONFIG` 指向权限 0600 的个人 JSON 文件；该文件和凭据不提交，不提供可提交的配置模板。
所需字段：

| 字段 | 含义 |
| --- | --- |
| `handoff` | 本轮 Fresh handoff 的绝对路径 |
| `adminEmailFile`、`initialPasswordFile` | 初始管理员凭据文件的绝对路径，权限 0600 |
| `gateway` | 本轮随机回环 Gateway Origin，仅供产品外攻击和权威观察 |
| `mailbox` | 本轮 Mailpit 的回环管理 Origin，仅用于读取真实投递邮件 |
| `controlDirectory` | 本轮专用、权限 0700 的 Redis 控制交接目录 |
| `httpsPort` | 可选的独立 HTTPS Edge 回环端口；Chrome 保留正式域名与 TLS 校验，通过进程内 DNS 映射访问 |

环境方在另一个进程运行 `node scripts/token-security-redis-control.mjs <handoff.json> <控制目录>`。
它按 runId 和 requestId 响应停止/恢复请求，只操作同项目唯一 Redis；校验容器来源与专用卷，
恢复必须达到 healthy，异常退出和故障租约到期也尝试恢复。前端不运行 Docker、Maven、数据库命令或环境清理。

初始管理员从 Console 登录、改密、创建并激活 Quota/Plan；两个 Tenant 分别初始化不同管理员，
各自通过真实邮件 Password Setup 建立密码，再登录选择自己的 Tenant。业务资源不由 API 或 SQL 预建。
Password Setup 必须等待 HTTP 204 与页面成功提示，两名成员实际登录并选择自己的 Tenant 后才记录前置通过。

## 判定与证据

- **Tenant 越权**：目标是另一真实身份当前使用的 Membership。确认目标存在且不在攻击者的权威可用列表；
  产品外提交 `type=TENANT` 和目标 Membership，要求 `403 TARGET_CONTEXT_UNAVAILABLE`；
  立即核对 Session、revision、activeContext 未改变，真实浏览器 Refresh 后再核对原 Tenant，不能只检查菜单。
- **错误 Token**：独立 Receiver 请求必须返回认证错误码；网络错误、CORS 或上游失败不计认证拒绝。
- **Refresh 重放**：轮换前 Cookie 仅存测试进程内存，页面触发真实轮换；超过既有重试租约后重放，
  验证 `401 SESSION_INVALID`，随后验证仍未到期的 User Access Token 被拒绝及页面隐藏受保护内容。
- **Redis 故障及恢复**：专用新会话先证明真实消费成功；停止同轮 Redis 后核对 503 与页面失败关闭，
  不显示密码错误；恢复后真实消费成功，再从 Console 退出、登录、选择和刷新恢复权威状态。
- 每个探针记录场景、路径、method、Trace、状态及时间。预期浏览器错误只按具体阶段、请求、错误码和时间匹配；
  未知错误、pageerror、正常请求失败或恢复后错误阻断通过。报告只保留散列资源别名，不输出凭据或响应正文。
- 前端报告保留 handoff SHA-256、runId、代码/dirty/Client 来源与 Chrome 版本；本轮是安全专项，
  不把 handoff 冒烟成功或此票通过当作父 #183/#189 全部聚合通过。

## 执行记录

2026-09-29：**本地真实安全专项 7 项全部通过**，标准、需求两路审查的已发现问题均已修复。
未推送、未运行完整 CI、未关闭 Issue；不据此宣称父 #183/#189 聚合验收通过。

最终 runId 为 `e18a1cd7-ebdf-45a7-84ef-5132d6028557`，执行时间为 UTC 09:48:07–09:50:20。
后端构建来源 `f79416f`、前端 `f25c2a8`，均带工作区修改；完整 commit、dirty、镜像、JDK 17、
Chrome `154.0.8037.58` 和正式 Client `0.4.0` 来源分别记录在
[Fresh handoff](issue-185-evidence/final/handoff.json) 与 [真实安全报告](issue-185-evidence/final/security.json)。
实际执行脚本的 SHA-256 及同轮关联见 [correlation](issue-185-evidence/final/correlation.json)。

| 验收要求 | 本轮事实与判定 |
| --- | --- |
| 错误、已撤销但未过期的 User Token | 独立消费明确返回 `401 ACCESS_TOKEN_INVALID`；同一 Token 在重放前消费为 200，撤销检查时再次断言尚未过期 |
| Refresh 轮换前凭据重放 | 真浏览器轮换后超过既有重试租约，重放返回 `401 SESSION_INVALID`；随后 Token 被撤销，Console 完成登出并返回登录页，工作台消失 |
| 其他 Tenant 越权 | 另一身份真实有效的 Membership 不在攻击者可用列表；请求返回 `403 TARGET_CONTEXT_UNAVAILABLE`；拒绝后 Session/revision/上下文不变，浏览器刷新后仍是原 Tenant |
| Redis 失败关闭与恢复 | 本轮 Redis 确实停止；独立消费从 200 变为 `503 TOKEN_REVOCATION_STATUS_UNAVAILABLE`；Console 隐藏工作台且不报密码错误；恢复 healthy 后消费 200，退出、重登、选择、刷新全部成功 |
| 独立场景与注入关联 | 产品外攻击请求记录 method/path/Trace/时间/状态码；Redis 停止和恢复各有同轮 requestId；资源只保留散列别名 |
| 同轮 Fresh 与 Console 前置 | 新容器、网络和独立数据卷通过来源校验；套餐、两个 Tenant、管理员初始化、真实邮件 Password Setup 和两成员登录均由本轮 Console 完成 |
| 版本与错误分类 | 来源记录齐全；意外 HTTP 响应 0、未知浏览器错误 0；Redis 的 503 是独立消费探针结果，Console 单独核对失败关闭和恢复 |
| 必要回归 | 本地相关检查通过，完整 Maven/CI 边界如下，未把 Mock UI 或局部检查等同于完整验收 |

[后端独立探针](issue-185-evidence/final/backend-probe.json) 另行证明同轮公钥一致、受信 Origin 允许、非受信 Origin 拒绝。
Fresh handoff 是 ready 时刻的来源快照，不表示环境持续在线。

### 保留的失败记录

1. [首次执行](issue-185-evidence/diagnostic-1/security.json)：HTTPS 导航超时，尚未发出业务请求。
   用户授权暂停旧 #208 的 11 个运行容器后，本轮 HTTPS 恢复 200；资源争用是受该对照支持的诊断方向，未确定唯一根因。
2. [第二次执行](issue-185-evidence/diagnostic-2/security.json)：Password Setup 点击后过早离开页面，随后成员登录 401。
   原报告中的前置 passed 不成立；已改为等待 204、成功提示和两成员实际登录，未篡改历史报告。
3. [第三次执行](issue-185-evidence/diagnostic-3/security.json)：Tenant 越权通过，但缺少 Gateway 测试路由 overlay，错误 Token 探针返回 404。
   已修正验收构建方式，并在消耗一次性前置前增加路由预检。
4. [第四次执行](issue-185-evidence/diagnostic-4/security.json)：重放及未过期 Token 撤销均返回明确 401，Console Bootstrap/Logout 成功。
   脚本错误地只等待 blocked 提示而超时；对照当前 ENDING 协议修正为断言匿名登录页与工作台消失。

最终成功轮次独立重建全部业务前置，没有重用以上失败轮次的业务数据。所有失败轮次数据卷保留。
收尾时先确认本轮 Redis 已恢复 healthy，再停止本轮全部 12 个容器及专属前端进程，保留数据卷。
此前获准临时暂停的旧 #208 共 11 个容器已恢复运行；只核对容器运行状态，未重新验收旧环境完整就绪。
见 [环境恢复记录](issue-185-evidence/final/environment-restoration.json)。

### 其他验证边界

- 本地通过：后端工具测试 62 项，前端 Node 测试 223 项，类型、语言、Lint、生产构建，
  Chromium Mock HTTP UI 测试 13 项（含键盘、焦点、无障碍与错误守卫）。Mock UI 不能替代真实 Chrome 安全验收。
- 后端完整 Maven verify **失败**：仓库规范检查遍历到已有 Git 忽略历史副本中的 Mapper XML。
  未删除该副本；在受控源码快照补跑仓库规范测试 19 项通过，不能据此改写完整 verify 的失败结论。
- 完整 CI、父 #183/#189 聚合验收未执行。结构化结果见 [本地检查](issue-185-evidence/checks.json)。

证据文件的 SHA-256 见 [manifest](issue-185-evidence/manifest.json)。凭据、Cookie、Token、邮件内容与原始运行日志不提交。
