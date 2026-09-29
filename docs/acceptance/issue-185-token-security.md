# #185 Token、Tenant 越权和 Redis 专项验收

本票沿用 #183 已确认的真实 Chrome、JDK 17、受信 HTTPS 与隔离 Fresh Compose 边界。
旧 #208 的 `unauthorized-platform-context` 只证明平台权限拒绝，不能代替其他 Tenant 的越权拒绝。
本轮不改产品授权、Refresh 重放或撤销语义。

## 可重复入口

后端先按 [环境交接](independent-verification.md) 准备新的独立项目，启用统一 Console，配置真实邮件与既有
`platform-mechanism-receiver` 非生产接收端。所有服务使用当前代码构建，环境方保留 preparation/handoff。
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

2026-09-29：验收入口已实现并完成标准、需求两路代码审查；**本票真实安全验收尚未通过，不能关闭**。

- 后端基线 `2dbf81612ec75d26ff140f8cca443081c27ad579`，前端基线
  `357cc25d9aca2e1bff3a0dd375eaa9d0d094f751`；实测时均包含未提交工作区内容，来源见报告。
- 本轮独立 Fresh 环境完成 ready 校验，JDK 17，真实 Chrome `154.0.8037.58`。
  [handoff](issue-185-evidence/handoff.json) 是该时刻的环境快照，不表示环境持续在线。
- [首次真实执行](issue-185-evidence/diagnostic-1/security.json) 在 Console 首次导航阶段超时，
  未发出业务请求；Tenant 越权、错误 Token、Refresh 重放、未过期 Token 撤销、Redis 故障及恢复均未执行。
  该原始失败报告的空 checks 数组不表示通过；随后入口改为预置逐项 `not-run` 状态。
- 诊断中本机前端 HTTP 可达，但独立 Edge HTTPS 握手超时，Docker 诊断也出现持续卡顿。
  资源争用仍是假设，尚未证实。为释放资源已停止本轮六个应用容器，保留全部数据卷；未操作旧验收或开发环境。
  继续排查若需停止旧 #208 项目，须先取得该项目的操作授权。
- 本地通过：后端工具测试 62 项，前端 Node 测试 223 项，类型、语言、Lint、生产构建，
  Chromium Mock HTTP UI 测试 13 项（含键盘、焦点、无障碍与错误守卫）。Mock UI 不能替代真实 Chrome 安全验收。
- 后端完整 Maven verify **失败**：仓库规范检查遍历到已有 Git 忽略历史副本中的 Mapper XML。
  未删除该副本；在受控源码快照补跑仓库规范测试 19 项通过，不能据此改写完整 verify 的失败结论。
- 完整 CI、父 #183/#189 聚合验收未执行。结构化结果见 [本地检查](issue-185-evidence/checks.json)。

证据文件的 SHA-256 见 [manifest](issue-185-evidence/manifest.json)。凭据、Cookie、Token、邮件内容与原始运行日志不提交。
