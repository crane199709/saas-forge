# #208 单 Console 跨仓验收记录

状态：**待验收，不关闭 #208**。已提交并推送 Audit Trace 修复和前端无障碍修复，两仓最终代码 CI 通过，最终组合的原生启动及真实页面读取通过。完整 Fresh 主链发现前端缺陷后版本发生变化；此前主链不得替代最终组合的全新 Fresh 复验。

## 最终代码与交付边界

| 项目 | 版本 |
| --- | --- |
| 后端产品代码 | `5f48e956a24395091860f316fe76d3d8736d81c3` |
| 前端产品代码 | `ee0f1a75c04296e0139ff10cbeb38017d8ef067b` |
| 共享 Client | `@crane199709/saas-forge-api-client@0.4.0`，源提交 `ebff4b338d0c4b39e51488ac1e9b98885318d438` |
| Soybean 固定基线 | `7613bd206cd42001b40e3eafceeb895dcbc277a8` |
| 浏览器 / JVM | Google Chrome `154.0.8037.58` / JDK `17.0.12` |

两仓各自使用独立干净副本。后端原工作区已有 `.dockerignore` 修改，未纳入本次提交。前端使用自身 dev 命令连接已准备的兼容后端，没有接管开发者进程。专用原生验收使用已验证的 `target/classes` 与依赖 classpath 启动五个 Spring Boot Main；逐一比对 class 与验证制品一致，确认进程命令、JDK 和真实 HTTP 就绪，不以磁盘文件推定旧进程加载版本。

## 需求映射

| 条件 | 当前证据与结论 |
| --- | --- |
| AC1 两仓干净构建、原生启动、独立交付 | 最终代码通过。后端 725 tests；前端 typecheck、lint、216 tests、build；原生五 Main 与最终前端真实登录、平台选择、Tenant/套餐读取通过，浏览器错误 0。见 `native-final-combination.json`、`final-build-ci.json`。 |
| AC2 身份、上下文、多标签、退出、晚到响应 | 第二轮真实 Fresh 已覆盖平台、Tenant、双身份、无权限、初始改密、两 Membership、Refresh 重放、跨标签整体退出/换账号与晚到 HTTP 200。该轮始于旧前端 `f4db8fd`；最终前端完整 Fresh 待复验。 |
| AC3 页面业务主链 | 第二轮真实页面完成额度/套餐/Tenant/订阅/初始化/邮件设置密码/通知/工作台、生命周期和 OAuth 管理/恢复/真实消费。通知页面重发完成，但第二轮未另取 Mailpit 两封计数证明。最终组合待复验，不借用第一轮计数。 |
| AC4 Fresh、安全、Redis、OAuth 时间、Audit | 后端修复版第二轮通过对应观察；三类实际 Audit 均有 Actor 和 Trace。Redis 故障拒绝及恢复、时间与身份夹具有脱敏记录。最终前端完整 Fresh 仍待复验。 |
| AC5 Soybean、品牌、语言、格式化、键盘/焦点/a11y、Remote | 实测中英文、Remote 两版资源与卸载、超长小数/尾零/金额/日期、Tab/Enter 可见焦点、文本转义、冻结确认 Escape/焦点恢复；修复图标按钮缺名称及空定位节点占据 Tab 顺序。最终原生页面实际可访问名称查询无空名称按钮。尚不能宣布固定基线完整视觉/品牌/全部键盘入口通过；实际单 Console 页签列表为空，未制造不存在的页签菜单入口作为实测证据。 |
| AC6 拒绝关联、异常阻断、敏感材料 | 第二轮 8 条浏览器错误均已关联：401 按同上下文、场景、路径及两秒内时间窗对应；服务器已提交后的响应丢弃按场景、路径及两秒内时间窗对应，未知错误 0、pageerror 0；所有新上下文在创建页面前安装监听。原生最终组合错误 0。参见 `second-round/error-classification.json`。 |
| AC7 区分执行类别与版本 | 本地自动化、真实 HTTP/Chrome、故障注入、CI 分开记录；未执行项明确保留。第二轮包含诊断中前端变更，不作为最终组合总体通过。 |
| AC8 长期证据和父票边界 | 本文、历史记录及 JSON 保留对应版本与散列清单。未修改或关闭 #201、#183–#189；#208 保持待验收。 |

## 本轮修复及验证

1. Console v2 登录和上下文切换将已有 HTTP Trace 显式传递到应用服务及 Outbox，修复 Audit Trace 丢失。HTTP 回归先红后绿，验证原请求 Trace 与同键重放不增发事件。后端完整 Maven verify：725 tests，0 failure/error/skip。
2. 图标按钮以现有 tooltip 提供默认可访问名称，显式 `aria-label` 仍可覆盖；空菜单定位节点移出 Tab 顺序及可访问树。原生最终页面使用实际角色/可访问名称查询，空名称按钮为 0。前端 typecheck、lint、216 tests 和构建通过。
3. Standards / Spec 两路独立静态审查均无必须修复项；审查不替代真实键盘验收。

| 类别 | 版本 / 结果 |
| --- | --- |
| 后端 CI | `5f48e95`：[36511629634 全部成功](https://github.com/crane199709/saas-forge/actions/runs/36511629634)。Nacos 作业首次受 Maven Central 502 影响，重跑后成功；其余两作业成功。 |
| 前端 CI | `ee0f1a7`：[36514362774 成功](https://github.com/crane199709/saas-forge-web/actions/runs/36514362774)。 |
| 最终代码本地构建 | 后端完整 verify 与前端类型/Lint/216 tests/build 通过；前端提交后重新构建，干净副本无改动。 |
| 最终组合原生真实 Chrome | 五 Main 启动、登录/上下文、Tenant/套餐读取、实际按钮名称检查通过；无接口错误或浏览器异常。 |
| 第二轮 Fresh 真实诊断 | 后端 `5f48e95`，前端从 `f4db8fd` 开始；4 条初始主链检查和 29 条后续检查，期间发现并修复 a11y；不得标成 `ee0f1a7` 全链通过。 |
| 最终组合全新 Fresh | 未执行。现有管理员 bootstrap 只能初始化一次，不能在已使用环境伪造全新首次凭据。新增隔离环境及沿用限定注入范围等待确认。 |
| 模拟/本地测试 | 属于自动化测试层；没有用它们替代实际 Chrome、Gateway、Nacos、Receiver、Redis、Kafka、Audit 或 Mailpit。 |

## 第二轮关键实际观察

- 创建和轮换在 CDP 响应阶段确认服务器成功后丢弃响应，真实页面各恢复一次；替代前 Secret 被拒绝，轮换前稳定 Secret 继续可用；第二次、跨操作者和期限外恢复被拒绝。
- 正常 OAuth 消费经 Gateway、Nacos、Receiver/Starter 与 Redis；Scope 不足 403。吊销复验等待服务端实际 204 后，签发与原有未过期 Token 均 401。首次脚本仅等待 700ms 后探测，属于同步错误，未计通过。
- overlap 原始有效期为轮换完成后 86,400 秒，恢复未延长；24 小时和 10 分钟边界使用受保护单记录时间注入，恢复原值，**未实际等待这些时长、未改宿主时钟**。
- 英文前台冻结后 26,955ms 隐藏工作台；旧 Token 401，解除冻结不恢复旧会话，真实重新登录恢复访问。生命周期通过页面恢复。
- Redis 停止时，真实消费 60,312ms 后返回 503，Console 显示“无法确认当前会话，已隐藏受保护内容。”；Redis 恢复健康后消费 200。旧 Console 会话被权威判为 `SESSION_INVALID`，整体退出并重新登录后业务页面可用；不声称无重新登录即恢复。
- 错误 Token 401；越权平台切换 `403 TARGET_CONTEXT_UNAVAILABLE`；Refresh Lease 后重放 `401 SESSION_INVALID`，相应未过期访问 Token 失效。
- 最终审计只读快照含 Session Started 18、Tenant Created 3、Tenant Context Switched 2，三类均有 Actor、Resource 和 Trace；使用同一散列别名方案脱敏。后续原生登录会继续产生事件，快照时间范围由各行记录确定。
- 脚本中途出现过重复名称定位、恢复入口选择、旧会话恢复路径及吊销等待错误，保留 `driverFailures`；修正后对应断言通过，不把脚本失败清除或伪装为首次通过。

## 留存与后续

第一轮记录见 `issue-208-first-round.md`；第二轮见 `issue-208-evidence/second-round/`。所有证据散列见 `issue-208-evidence/manifest.json`。历史 Fresh、当前原生和最终待复验相互分开。

已完成的 OAuth 时间、角色、Membership 和 Redis 注入均有恢复记录。旧环境及数据保留，没有执行 `down -v` 或数据清理；当前专用原生进程与依赖为继续验收保留。下一步是在最终版本组合上完成获准的全新 Fresh、补齐视觉/品牌及适用键盘覆盖，再判断是否满足关闭条件。
