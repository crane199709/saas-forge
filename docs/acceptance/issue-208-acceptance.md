# #208 单 Console 跨仓验收记录

结论：最终代码组合已完成本轮 Fresh、原生启动及所列适用回归，**产品代码与最终证据两路复核通过，待本票状态回写**。初次诊断失败保留；经修正验收准备或脚本后重测通过，不宣称首次全部成功。不自动修改或关闭 #201、#183–#189。

## 固定版本与执行边界

| 项目 | 版本 |
| --- | --- |
| 后端产品代码 | `5f48e956a24395091860f316fe76d3d8736d81c3` |
| 前端产品代码 | `ee0f1a75c04296e0139ff10cbeb38017d8ef067b`，served HTML provenance 与干净副本一致 |
| 共享 Client | `@crane199709/saas-forge-api-client@0.4.0` |
| Soybean 基线 | `7613bd206cd42001b40e3eafceeb895dcbc277a8` |
| 最终 Fresh runId | `46dde443-c012-4a49-8a71-3beb50693057` |
| Chrome / JVM | `154.0.8037.58` / JDK `17.0.12` |

后续后端提交仅追加验收文档，不改变上述产品代码。两仓各自使用独立干净副本；原工作区已有 `.dockerignore` 修改始终未纳入本次提交。前端使用自己的原生 dev 入口连接已准备后端，没有接管开发者进程。Fresh 的网络、数据卷、镜像标签、JDK、HTTPS Gateway 与公钥摘要由既有 handoff 工具核验。

正常业务资源均经真实 Console 创建；部署引导沿用既有平台管理员和保留服务身份入口。安全探针、单记录到期注入及临时身份授权夹具在产品外运行，只作用于本轮独立项目。复用既有 Fresh、浏览器、Remote 与平台机制 Receiver 验收能力，没有新增生产接口或重复验收框架。

## 验收条件映射

| 条件 | 结果与直接证据 |
| --- | --- |
| AC1 两仓干净构建、原生启动、独立交付 | 通过。后端完整 verify：725 tests；前端 typecheck、lint、216 tests、build；五个 JDK Main 直接使用与验证制品逐 class 一致的产物启动，最终前端真实登录、平台选择、Tenant/套餐读取通过。见 `native-final-combination.json`、`final-build-ci.json`。原生证明对应同一产品组合，独立记录，不冒充 Fresh 主链。 |
| AC2 身份、上下文、多标签、退出、晚到响应 | 通过所列场景。Fresh 中文初始改密、平台、Tenant、双身份、无可用上下文、两 Membership、完成切换后刷新、跨标签整体退出与换账号；旧平台 HTTP 200 暂扣至新账号生效后释放，不恢复旧身份。见 `release-fresh/main-chain.json`、`business-chain.json`、`browser.json`。 |
| AC3 真实页面业务主链 | 通过。额度定义激活、正数套餐、三个 Tenant、Subscription、管理员初始化、真实邮件 Password Setup 与正常登录、Tenant 工作台、通知重发后两封实际邮件且初始化仍成功；生命周期、OAuth 管理/恢复及真实消费。 |
| AC4 同轮 Fresh、安全、Redis、时间、Audit | 通过。错误/撤销 Token、Refresh 重放、越权上下文、Scope、冻结/解除冻结、Redis 故障与恢复、OAuth 到期注入；三类浏览器事实分别精确匹配已发布 Outbox 与 Audit 的 event/actor/resource/trace。见 `audit-correlation.json`、`injections.json`、`business-chain.json`。 |
| AC5 固定基线视觉、品牌、语言、格式化、键盘/焦点/a11y、Remote | 通过适用覆盖。与固定基线相比 theme 2 文件、styles 7 文件、materials 20 文件保持原样；真实页面实测顶栏 56px、侧栏 220px、主色 `100 108 255`、SaaS Forge 品牌/Favicon、无水平溢出和无空名称按钮；登录 Tab 顺序、创建抽屉和生命周期弹窗的 Enter/Escape/焦点返回；中英切换保留表单且不提交，刷新后偏好保留；Remote 超长小数/尾零/金额/日期、可见键盘焦点、文本转义、v1/v2 实际 CSS/图片/匿名请求与卸载。见 `visual-keyboard.json`、`soybean-baseline.json`、三张脱敏截图及业务记录。 |
| AC6 预期拒绝、异常阻断、敏感材料 | 通过当前范围。13 条浏览器错误中，10 条对应已知注入/拒绝，3 条属于已修正并复验的诊断；未知 0、pageerror 0。逐项分类保留，不全局忽略 401/403/503；最终版本的 handoff smoke 无异常。Secret/Token/Cookie/密码/Challenge 不写入证据，发布前扫描已知敏感值。 |
| AC7 执行类别与版本 | 通过。4 个初始主链步骤、31 种后续检查以及9项既有 handoff smoke 独立列示；重复执行不计为新场景。真实浏览器、真实独立 HTTP 探针、到期状态注入、本地自动化和 CI 分开，历史旧 SHA 仅作诊断。 |
| AC8 长期需求映射和父票边界 | 已产出本文、分轮次 JSON、图片与 SHA-256 清单。父票不自动处理；本票的外部回写/关闭另行确认。 |

## 关键实际结果

- 初始管理员通过中文 Console 完成受限登录和改密，再完成 Quota/Plan/Tenant/订阅/初始化。真实 Mailpit 邮件完成成员密码设置；同一成员通过真实产品路径取得两 Membership，切换完成后刷新恢复权威上下文。
- 双身份与无权限使用专用授权夹具；最终只读复核平台角色为 0、两个 Membership 均 ENABLED、三个 Tenant 均 ACTIVE。到期字段与保存原值再次比对，已恢复；Redis 恢复健康且真实消费 200。
- 英文前台冻结后 **26,986ms** 收回工作台，旧 Token 401。真实 `DELETE .../suspensions` 解除冻结后，旧会话仍失效，重新登录可进入工作台。
- OAuth 创建/轮换及新旧 Secret 经 Gateway → Nacos → Receiver/Starter/Redis 消费成功，Scope 不足 403。吊销在服务器确认 204 后，新签发和已有未过期 Token 均 401。
- 创建与轮换在 CDP 响应阶段确认服务器已提交后丢弃响应，再从产品原操作入口一次恢复；被替换 Secret 无效，原稳定 Secret 仍有效。重复、跨操作者与超过十分钟期限的恢复被拒绝。
- 原 overlap 为轮换提交后 **86,400 秒**，恢复未延长。24 小时/10 分钟边界使用单记录到期状态注入，并恢复原值；**没有实际等待这些时长，也没有改宿主时钟**。
- Secret 关闭后消失；刷新、离开后返回，以及同一 Console 另一标签页整体退出均不能重读展示过的 Secret。存储中未出现已知 Secret。
- Redis 故障中真实消费 503，Console 显示无法确认会话并隐藏受保护内容。503 与页面反馈完整观察耗时 **69,211ms**；恢复后真实消费 200，整体退出并重新登录后业务页面可用，未重启应用。
- 通知重发返回 204，持久化 Mailpit 中实际收到 **2 封**对应邮件；初始化仍成功。
- Session Started、Tenant Created、Tenant Context Switched 各取本轮真实浏览器资源精确关联：同一 source event、actor、resource、trace 在已发布 Outbox 和 Audit 一致。证据使用一致散列别名，不用任意历史 Audit 行充数。

## 修复、自动化与 CI

本次产品修复只有两组：后端将 Console v2 的 HTTP Trace 传入既有事件工厂及 Outbox；前端图标按钮以 tooltip 提供默认可访问名称，空菜单定位节点移出 Tab 顺序和可访问树。没有改变业务授权、Token 语义、数据库结构或事件类型。

| 验证 | 结果 / 范围 |
| --- | --- |
| Trace HTTP 回归 | 先红后绿；登录、Tenant 切换及同键重放观察原始 Trace 和事件数。 |
| 后端本地完整 verify | 725 tests，0 failure/error/skip，最终产品代码。 |
| 前端本地完整验证 | typecheck、lint、216 tests、build 通过，最终产品代码。品牌外域/路径逃逸/不完整 Profile/不可读颜色、错误 MIME/Favicon 回退属于自动化测试；不冒充真实定制品牌配置。 |
| 后端产品代码 CI | [36511629634 成功](https://github.com/crane199709/saas-forge/actions/runs/36511629634)，含 JDK17、Fresh 生命周期、Nacos 权限。首次 Nacos 作业遇 Maven Central 502，失败作业重跑成功。 |
| 前端产品代码 CI | [36514362774 成功](https://github.com/crane199709/saas-forge-web/actions/runs/36514362774)，提交 `ee0f1a7`。 |
| 上轮证据提交 CI | [36515417399 成功](https://github.com/crane199709/saas-forge/actions/runs/36515417399)，提交 `2b47a4b`。 |
| 两路代码审查 | Standards / Spec 均无必须修复的产品代码问题；本轮最终证据补审同样无必须修正项，审查不代替独立重跑。 |

## 诊断失败、复验及限制

首次执行不是全绿。原始请求、错误和 `driverFailures` 均保留，说明见 `release-fresh/business-chain.json` 的 `diagnosticExplanations`：

- 脚本在上下文切换/Refresh 尚未完成时强制刷新，出现 `SESSION_INVALID` 并隐藏受保护内容；等待真实 Refresh 完成后重跑切换及刷新通过。**不据此承诺进行中切换被导航打断时会话仍连续有效**。
- 普通生产构建清理 dist 后，独立 Remote 验收静态制品未重新准备；平台机制 Receiver 的 profile 也未显式启动。补跑既有制品校验构建、启动 Receiver 后，真实资源/消费重测通过；初次缺依赖不算产品通过。
- 脚本先后漏点通知确认、未接受接口实际 204、等待了错误的解除冻结 method/path、未等待按钮就绪、误用诊断 SQL 列名。修正后对应行为均重新确认；首次“restored-via-console”只是尝试标记，之后以真实 DELETE 200、权威状态和再次完整生命周期验证确认恢复。
- 真实视觉与品牌覆盖限于当前产品可配置的默认平台品牌及 Tenant 默认回退、中文/英文、桌面 Chrome。自定义 Profile 安全策略另由自动化覆盖；未声明完整 WCAG 认证或额外浏览器/JDK 矩阵。当前单 Console 无实际页签条目，不制造页签菜单入口充当真实验证。
- Remote 生命周期探针调用 dev 公开加载器并使用真实静态资源；受控 Receiver 标记为平台机制验收，不声称生产业务闭环。

## 留存

`issue-208-evidence/release-fresh/` 为最终组合本轮证据；根目录历史 JSON、`second-round/` 和 `issue-208-first-round.md` 仅保留诊断历史。`manifest.json` 给出版本分组和文件散列。

旧环境已停止并保留其数据卷；上轮原生端口切换重建 Mailpit、丢失内存测试邮件的影响没有抹除。本轮在创建时即挂载专用 Mailpit 数据卷，测试邮件和业务数据均保留。未清理环境/数据，未接管现有开发服务；用户原有 `.dockerignore` 改动保留。
