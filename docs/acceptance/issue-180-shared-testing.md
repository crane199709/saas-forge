# Issue #180：共享前端测试基线验收

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

- 规格：[Issue #180](https://github.com/crane199709/saas-forge/issues/180)。
- 实现起点：`b49abfee0f4e2620755ba6202a3b74e15d4e86fe`；本记录对应当前实现工作区。
- 覆盖登记与复现命令：[共享前端测试基线](../console-testing-baseline.md)。
- 日期：2026-09-13。本地执行、远端 CI 与 Fresh 产品验收分别记录；未执行不能视为通过。

## 组件、交互与视觉

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| Runtime 认证与会话协调双语环境 | 通过，208/208 | 本机 `/tmp/issue180-runtime.log` |
| AuthenticationShell 双语及显式语言切换 | 通过，34/34 | `/tmp/issue180-review-tests.log` |
| 四个公开恢复面板双语 | 通过，64/64 | `/tmp/issue180-operation-final.log`；包含状态文案、失败/空态、允许/禁止恢复、忙碌防重、失败重试、处理中重读与游标分页 |
| 共享 Shell 会话退出表单保护 | 通过，8/8 | `/tmp/issue180-review-tests.log`；双语脏表单继续/放弃、清洁/保存/卸载后退出 |
| 新稳定画面浏览器矩阵 | 通过，72/72 | `/tmp/issue180-stable.log`；双语、浅深色、桌面/窄屏，每例 axe 与行为断言 |
| 认证恢复与失败消费者矩阵 | 通过，16/16 | `/tmp/issue180-auth-browser.log`；每例恢复中→失败→显式重试→匿名登录 |
| 首次 Linux 候选生成及审阅 | 通过，审阅 129 张（历史 Design System 代际，见下注） | `.scratch/issue-180-visual/run.tcWJ0N`；使用九张联系表逐项检查文字、布局、内容与焦点后复制 Linux PNG |
| Linux 正式比对 | 通过，94 个组件＋40 个消费者测试，0 跳过（历史 Design System 代际，见下注） | `.scratch/issue-180-visual/run.CG8apB/{components,consumers}.json`，`exit-code.txt=0`；无 `--update`，129 张权威基线 |
| 缺失基线负向验证 | 按预期失败 | `.scratch/issue-180-visual/run.AF6KOs`，`exit-code.txt=1`；未生成/自动接受缺失基线 |

上表「首次 Linux 候选生成及审阅」「Linux 正式比对」两行的「129 张权威基线」「94 个组件＋40 个消费者测试」及 `.scratch/issue-180-visual/run.CG8apB/{components,consumers}.json` 属于已被删除的自建 Design System / React Shell 代际（该代际随后被 Vue 3 + Element Plus Console 取代）：组件阶段测试随 `consoles/shared/design-system` 及其 `vitest.browser.config.ts` 一并移除，`.scratch/issue-180-visual/run.CG8apB/` 只是仓库未跟踪的 `.scratch/` 本机历史运行目录。现行 `scripts/run-console-visual-container.sh` 只调用 `vitest.consumers.browser.config.ts` 并只产出 `/evidence/consumers.json`，仓库中已不存在组件阶段入口、`components.json` 或 `consoles/shared/design-system`，因此上述组件测试数字与 129 张基线无法在当前仓库复现。本记录保留原数字作为历史事实。当前存活的视觉基线只有 `consoles/browser-test/__screenshots__/admin.browser.test.ts/` 下的 8 张 PNG：认证登录与恢复各中英文两张（`authentication-{en-US,zh-CN}-{login,recovery}-chromium-linux.png`），以及 Soybean 布局在 1024 / 1440 两个宽度下的浅色与深色各一张（`soybean-layout-{light,dark}-{1024,1440}-chromium-linux.png`）。现行入口为 `pnpm --dir consoles run test:browser:consumers` 与 `bash scripts/verify-console-visual.sh`，见 [共享前端测试基线](../console-testing-baseline.md) 和 [测试策略](../13-testing-strategy.md)。

首次红测暴露并修复了三个实现缺陷：启动 Spin 的可访问标签缺少合适角色、Skeleton 向辅助技术暴露空标题，以及危险/主按钮交互颜色对比度不足。修复复用现有语义颜色，后续本机与固定 Linux axe 检查通过。自动扫描与键盘检查是 WCAG 2.2 AA 工程证据，不等于正式无障碍认证。

候选生成曾因容器 Corepack 下载连接重置失败，重试成功；该失败未计作通过。普通浏览器入口默认不比较平台不同的 PNG，独立 Linux 入口和 CI job 强制启用视觉比较。历史 macOS 图片保留，更新基线仍须人工审阅。

## 完整构建与 Fresh Compose

`./mvnw --batch-mode --no-transfer-progress verify` 通过，耗时 8 分 14 秒，日志 `/tmp/issue180-maven.log`。前端类型、lint、格式检查、550 个工作区单测、边界检查和制品构建通过；本机组件浏览器 90 通过/4 截图专用跳过（组件阶段测试属于已删除的 Design System 代际，该数字不可复现），消费者浏览器 38 通过/2 截图专用跳过。六个截图专用用例已由上述 Linux 正式视觉门禁执行通过。

443 释放后复用现有 `deploy/compose/.secrets/local-https-development/server.pem` / `server.key`，四域 DNS、正常 TLS 校验与 Chrome 153.0.8010.36 导航预检通过。旧 `local-console-tls.pem` 缺少 Remote SAN，未用于正式验收；没有忽略证书错误或修改信任边界。

执行命令（只传文件路径，不输出密钥）：

```bash
SF_ACCEPTANCE_TLS_CERT="$PWD/deploy/compose/.secrets/local-https-development/server.pem" \
SF_ACCEPTANCE_TLS_KEY="$PWD/deploy/compose/.secrets/local-https-development/server.key" \
bash scripts/verify-console-authentication-e2e.sh --product
```

| 轮次 | 结果 | 记录 |
| --- | --- | --- |
| 初始预检 | 受阻，尚未创建环境 | TLS 路径未设置、443 被占用；`/tmp/issue180-fresh.log` |
| 首次真实 Fresh | 失败，38 通过/2 失败，0 跳过 | `/tmp/issue180-fresh-retry.log`；`sf-brand-evidence.aqGpiy`；通知 SMTP 恢复子测试在重新启动 Mailpit 后收到 503，预期 204，父测试随之失败 |
| 未改代码的独立 Fresh 复跑 | **通过，40/40，0 失败/跳过** | `/tmp/issue180-fresh-confirm.log`；`sf-brand-evidence.9ULzQI/acceptance-run.json` 的 `status=passed`、`commit=6cd6d83a716758ad754959c45bdb1329b2a52fae`、`dirty=false` |
| 重置数据卷后的 Chrome 浏览器门禁 | **通过** | 同一最终记录中 `compose-reset`、`console-browser-chrome` 均 passed，整个入口退出码 0 |
| 专属项目清理 | **通过** | 两轮项目的 Docker label 查询均无残留容器/卷：`saas-forge-console-1789310078-7455-ce5698`、`saas-forge-console-1789310444-9329-38a5bd`；未接管日常服务 |

最终运行覆盖 Platform 初始改密/登录/恢复/退出、Tenant Membership/Context、槽位与多标签竞争、中英文故障表单、Locale/品牌与安全拒绝路径。此命令复用前一节构建工件，没有重复执行 Maven/workspace 门禁。

首次 SMTP 恢复 503 在未修改代码的复跑中未复现，当时根因尚未确定；后续复现与修复见下节。本记录保留该间歇失败，不以重试通过证明其稳定性已解决；本轮没有为获得通过而跳过测试、延长超时或放宽断言。完整受限诊断保留在 `sf-console-e2e-diagnostics.7TR06F`，不得直接上传原始日志。

远端 [Verify 34791131200](https://github.com/crane199709/saas-forge/actions/runs/34791131200) 对应 `2969536`：JDK 17/Fresh Chrome、Tenant lifecycle Fresh 和 Nacos 三个 Job 通过；视觉 Job 的 94+40 个测试通过（94 个组件测试属于已删除的 Design System 代际，现不可复现），但临时目录清理失败，整个 Job 失败。当时 MVP 对应事项保持未勾选、Issue 保持 OPEN；最终状态见下节。

## 代码审查

- Standards：初次发现英文 Shell 测试未同步组件语言，已改为读取当前 Console Locale；复核剩余 0 项。
- Spec：初次发现恢复面板分支/文案及共享退出保护覆盖不足，已补齐并更新覆盖清单；复核剩余 0 项。
- 审查为源码核对；测试执行结果以上表及后续完整验收为准。

## SMTP 故障注入稳定性修复

后续在同一 Fresh 项目中重复通知场景，第 3 次复现恢复 503。失败前工作流无租约、已到重试时间且自动恢复暂停；调用后尝试次数增加，IAM 新增 `MailSendException → MessagingException`，排除该次为未领取到工作流。原始失败轮次缺少内部记录，下面是后续同类复现证据，不倒推其未记录的细节。

最小化到持续运行的容器内 JVM 后，定位到 `stop/start` 的 DNS 副作用：Mailpit 停止时 `mailpit` 被解析为非容器地址 `198.18.0.102`；重启后的容器地址及新进程系统解析均为 `172.24.0.2`，JVM 却仍使用旧缓存，SMTP 欢迎语阶段收到 `[EOF]`。HTTP 管理接口检查无法覆盖调用方 JVM 的解析状态。本机具体哪个 DNS/代理组件提供了非容器地址未在本任务中确定。

| 对照 | 结果 |
| --- | --- |
| 持续 JVM，停止期间发送，重启后仅等待 HTTP 就绪 | 15/15 恢复失败，SMTP bad greeting `[EOF]` |
| 同一探针增加调用方 SMTP 握手就绪检查 | 15/15 恢复成功；首次多等待 29.890 秒 |
| 使用 `pause/unpause` 保留容器网络与 DNS | 15/15 暂停期间真实投递失败，15/15 恢复后立即投递成功 |

修复选择 `pause/unpause`：该用例要注入的是 SMTP 无法处理邮件，不需要附带 DNS 服务名消失。保留初始化成功、投递待恢复、原操作者限制、原请求恢复 `204`、真实 Mailpit 收件与既有身份/幂等键不变的断言；`finally` 解除本次暂停。没有重启 IAM、修改 DNS 缓存策略、自动重放业务请求或放宽断言。

对照日志保留在本机 `/tmp/issue180-smtp-network/`，修复后的完整 Fresh 产品验证通过：Chrome 40/40、0 失败/跳过；重置数据卷后的 Chrome 浏览器门禁通过，整个入口退出码 0。日志 `/tmp/issue180-smtp-fixed-fresh.log`，证据 `sf-brand-evidence.S88f3W/acceptance-run.json`，本次为 `c192b3d` 上的修复工作区（`dirty=true`），不冒称远端当前 SHA CI 结果。临时诊断代码已移除并重建原始 IAM 制品。脚本格式、ESLint、语法及差异检查通过；Standards/Spec 两线审查无遗留项。

## Linux CI 临时目录权限修复

2026-09-14 检查上述 CI：视觉容器退出码为 0，随后宿主 `rm -rf /tmp/sf-visual.*` 大量报 `Permission denied`。原因是 Docker 默认 root 在 bind mount 中创建了依赖目录；GitHub Linux Runner 的普通用户无法删除 root 拥有的子目录。本机 Docker Desktop 的挂载权限行为未暴露该差异。

修复为视觉容器显式使用调用者 UID/GID；Corepack 入口与缓存使用容器临时 HOME，避免非 root 进程写入系统目录。继续由宿主清理自己拥有的临时目录，不增加 sudo、全局 chmod 或忽略清理错误，也不修改图片基线或测试阈值。

修复后的本机固定 Linux 视觉通过：94 个组件＋40 个消费者测试全部通过（94 个组件测试属于已删除的 Design System 代际，现不可复现；现行入口只产出 `consumers.json`），入口最终退出码 0；记录 `.scratch/issue-180-visual/run.tKUHd0` 与 `/tmp/issue180-visual-user.log`（本机未跟踪的临时证据，仓库外不可复现）。容器内核对进程、依赖目录及报告均为调用者 `501:20`；退出后临时目录 `sf-visual.fJDivn` 已不存在。Bash 语法及差异检查通过。远端修复提交 CI 后续已通过，见下节。

## 最终完成确认（2026-09-14）

实现提交 `c6b451484d4815db14d63d4e007474b540a4664d` 的 [Verify 34794778921](https://github.com/crane199709/saas-forge/actions/runs/34794778921) 已完成且成功：Linux Chromium 视觉、JDK 17/Fresh Chrome、Tenant lifecycle Fresh、Nacos 四个 Job 全部通过。视觉入口及清理最终退出码 0；Fresh Chrome 产品 40/40、0 失败/跳过。

据覆盖清单与本地/CI证据，Issue #180 的 16 项验收已完成，MVP 第 1 阶段对应共享测试事项勾选。此处仅完成本项，不代表其他阶段或其他业务闭环完成。此前失败、跳过及阻塞记录保留为历史事实。
