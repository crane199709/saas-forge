# #208 单 Console 跨仓验收记录

状态：**待验收，不能关闭 #208**。本轮发现并修复 Console v2 登录与租户切换的 Audit Trace 丢失；修复前的真实 Fresh 证据保留作诊断，不作为修复后最终组合的通过证明。

## 版本与边界

| 项目 | 本轮真实验收版本 |
| --- | --- |
| 后端 | `21385e3574ef8a08f65d1f58979a4138b80a210f` |
| 前端 | `f4db8fd804fa3f8afb67cd49d12812f72f77e3c6` |
| 共享 Client | `@crane199709/saas-forge-api-client@0.4.0`，源提交 `ebff4b338d0c4b39e51488ac1e9b98885318d438` |
| Soybean 固定基线 | `7613bd206cd42001b40e3eafceeb895dcbc277a8` |
| Fresh runId | `65da8356-45b3-4a2e-82c2-ff92aa6c4037` |
| 浏览器 / JVM | Google Chrome `154.0.8037.58` / JDK 17 |

后端、前端均从上述提交导出独立副本构建。原后端工作区已有 `.dockerignore` 修改，未纳入构建或本次提交。前端以原生 dev 命令启动，连接专用后端；没有接管开发者服务。真实域名、受信 HTTPS、Cookie、Gateway、Nacos、Redis、Kafka、Mailpit 和 Receiver 均参与验证。独立攻击探针使用测试进程内凭据，不向产品添加参数。

## 验收条件映射

| #208 条件 | 本轮证据 | 最终状态与限制 |
| --- | --- | --- |
| AC1 两仓构建、原生启动、独立交付 | 前端 frozen install、typecheck、lint、216 tests、build、原生 dev；后端干净基线 724 tests；现有原生 JDK17 进程与磁盘类文件比对 | 部分。磁盘比对不证明进程加载了最终代码；修复后的原生启动和最终组合尚待复验 |
| AC2 身份、上下文、多标签、退出、晚到响应 | 初始凭据改密；平台、租户、双身份、无可用上下文；同账号两 Membership；第二标签恢复、退出后刷新；旧平台 HTTP 200 响应延迟至换账号后释放，不恢复旧身份 | 诊断基线通过对应断言；最终组合待复验。晚到响应场景的完整 Console/pageerror 监听仍需补齐 |
| AC3 业务页面主链 | 额度定义、正数套餐、三个 Tenant、订阅、管理员初始化、真实邮件 Password Setup、重发、租户工作台；冻结/解除冻结；OAuth 创建/轮换/恢复/吊销及真实消费 | 诊断基线通过对应断言；最终组合待复验 |
| AC4 Fresh、安全、Redis、时间、Audit | 独立 Fresh；错误 Token 401；越权上下文 403；Refresh 重放 401 并撤销未过期 Token；Redis 503及恢复；受保护时间注入；真实 Audit | **阻断项已定位**：v2 Session Started 与 Tenant Context Switched 缺 Trace。修复后仍需新的完整 Fresh 证明 |
| AC5 Soybean、品牌、语言、格式化、键盘/焦点/a11y、Remote | 中英文页面；语言切换保留表单且不提交；英文错误提示、租户流程；Tab/Enter 登录及主标题焦点；真实 Remote v1/v2 CSS、图片与卸载；页面截图 | 部分。不能以截图存在或构建通过替代全套固定基线视觉、品牌、精确格式化和无障碍验收 |
| AC6 预期拒绝、异常阻断、敏感材料 | 脱敏请求状态、响应丢失记录、注入/恢复记录；产物扫描未出现已知密码、Secret、Token 或 JWT | 部分。已观测拒绝按场景列明；独立安全/晚到响应场景需补齐全过程异常观察。Redis 页面暂不可用文案未形成独立断言，不宣称该项通过 |
| AC7 本地/模拟/真实/CI 分开 | 下表与 JSON 区分范围及版本；修复后全量本地 725 tests 通过 | 最终修复提交 CI 未执行，旧 SHA 的成功 CI 不替代它 |
| AC8 长期需求映射与证据 | 本文与同目录脱敏证据清单、摘要散列 | 已记录当前事实；最终证据尚需追加。不修改/关闭 #201、#183–#189 |

## 已取得的真实结果

- 平台初始凭据经页面改密后重新登录。两个 Tenant 使用同一个专用管理员，通过真实 Mailpit 邮件完成密码设置，并在两个 Membership 之间切换及刷新恢复。
- 临时授予专用身份平台角色，验证双身份切换，以及跨操作者恢复拒绝；撤销专用身份的两个 Membership 后显示无可用上下文。夹具均恢复。
- 英文前台在冻结后 **14,878 ms** 收回工作台；旧 Token 返回 401，解除冻结不会恢复旧会话，重新登录可恢复访问。首次脚本使用重复标题定位和错误路径等待，已修正后重测；首次超时不计为产品失败或通过。
- OAuth 使用真实 Gateway → Nacos 发现 → Receiver/Starter/Redis 链。正常新旧 Secret 均可消费；Scope 不足返回 403；页面吊销后签发及原有未过期 Token 均返回 401。
- 创建/轮换响应在 CDP 响应阶段确认服务器成功后丢弃，再经页面一次恢复；被替代 Secret 拒绝、稳定 Secret 仍有效。第二次、跨操作者及超过恢复期限的恢复被拒绝。
- 24 小时 overlap 和 10 分钟恢复边界通过获准的单记录时间注入检查，保留原值、影响行数和恢复结果；**没有实际等待 24 小时/10 分钟，没有改变宿主时钟**。
- Redis 首次探针 15 秒客户端超时，未计为服务端拒绝。重测取得真实 503，耗时 **60,209 ms**；Redis 恢复健康后，服务调用 200，Console 恢复到可用页面，不需要重启应用。临时不可用页面文案尚未独立断言。
- 错误 User Token 返回 401；越权 PLATFORM 选择返回 `403 TARGET_CONTEXT_UNAVAILABLE`；Refresh Lease 后重放返回 `401 SESSION_INVALID`，对应尚未过期访问 Token 被拒绝，Console 不再显示工作台。
- 专用通知 Tenant 的重发操作结束，Mailpit 收到至少两封真实邮件，初始化仍成功。
- Audit 只读快照含本轮 Session Started 10 条、Tenant Created 2 条、Tenant Context Switched 1 条；快照在第三个 Tenant 创建前取得。Tenant Created 有 Trace，另外两类无 Trace。身份、资源和 Trace 在证据中使用一致散列别名，没有使用历史环境记录充数。

## 修复与验证

缺陷原因：Console v2 应用服务向两个既有事件工厂传入 `null` Trace。HTTP 入口现使用已有 Trace 解析规则，显式传到应用服务及 Outbox；不改变权限、Token、Cookie、数据库结构、事件类型或重放语义。

新增既有 `AuthenticationHttpIT` 的回归场景，从 HTTP 登录、Tenant 切换及同键重放观察 Outbox：两条事件保留各自原始请求 Trace，重放不增发事件或覆盖 Trace。

| 验证 | 结果 | 适用版本/限制 |
| --- | --- | --- |
| 修复前 HTTP 回归 | 失败，预期 Trace 实际为空 | 证明实际缺陷，不是网络或测试环境错误 |
| 修复后后端全量 Maven verify | 725 tests，0 failure/error/skip | 基线加本次四文件修复；不等于真实 Kafka/Audit 全链复验 |
| 前端 typecheck/lint/test/build | 通过，216 tests，0 skip | 前端提交不变；无前端产品改动 |
| 基线后端完整 verify | 724 tests，0 failure/error/skip | 初次 Redis 初始化超时后重试通过，最终完整重跑通过 |
| 工具测试 | 初次 60 passed / 2 failed；相关重跑 5/5 passed | 初始干净导出缺 Git 元数据；恢复元数据后重跑，不宣称首次通过 |
| Standards / Spec 静态独立审查 | 各 0 个代码发现 | 两位审查者均未独立执行测试；整个 #208 最终验收仍待完成 |
| 后端基线 CI | [成功运行 36497551683](https://github.com/crane199709/saas-forge/actions/runs/36497551683) | 仅 `21385e3`，不是修复提交 |
| 前端 CI | [成功运行 36404390450](https://github.com/crane199709/saas-forge-web/actions/runs/36404390450) | 仅 `f4db8fd` |
| 修复提交 CI / 第二轮最终 Fresh | 未执行 | 推送及新项目限定注入授权待确认 |

## 证据与后续

脱敏 JSON 见 `issue-208-evidence/manifest.json`。`browser.json` 是现有跨仓 handoff smoke；`business-chain.json` 是本轮定向真实检查，状态为 `partial`。两者均不是 #208 总体验收通过证书。UI/邮件正常操作经过真实页面；错误请求、时间修改、身份夹具是独立注入；本地测试中使用模拟的依赖不冒充真实服务。

第一轮专用容器已停止，容器、卷、受限凭据文件均保留；没有删除数据。OAuth 时间字段、专用角色与 Membership 夹具均恢复。初次角色夹具曾因返回值解析失败遗留一条新记录，已按原身份、角色及影响行数 1 精确恢复；注入记录保留该过程，后续夹具改为预分配 ID 并在 finally 恢复。

下一轮需在最终提交上：独立干净构建；原生启动的版本证据；新 Fresh 完整主链及三类 Audit Trace 关联；补齐视觉/品牌/精确格式化/a11y和全部异常观察；执行最终 SHA 的 CI。满足后再更新本票结论，不自动处理父票。
