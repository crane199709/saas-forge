# Issue #169：完整原生开发流程与默认文档

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。
>
> **模板裁定（2026-09-15）**：下表条目 2 与本页父规格映射第 1 项的"五份模板""模板检查"**已不成立**。仓库中没有本地配置模板文件，5 处 `maven-jar-plugin` 也不再排除 `application-local.yml.example`；「本地开发」规范与各服务原生启动说明统一为"配置由开发者在 Git 忽略的文件中自行维护，仓库不提供可提交模板"。此处只更正结论，原记录文字按历史事实保留。

2026-09-13，依据 [#169](https://github.com/crane199709/saas-forge/issues/169) 与父规格 [#161](https://github.com/crane199709/saas-forge/issues/161)。本轮开始 HEAD 为 `917039ff54a3d02389196bc7214af5247696d00b`，工作区干净。修改仅涉及开发与验收文档，没有修改应用代码、API、迁移、运行配置、CI 或应用生命周期。

默认入口为[原生本地开发](../native-local-development.md)，完整复现与 CI 覆盖统一见[分层验证](../local-verification.md)。根 README、贡献指南、Console 中英文 README、部署入口、Compose 中英文说明及脚本目录均区分原生日常流程与旧工具的集成验收职责。Console README 修正了日常启动重复生成 Client 及旧 JDK 矩阵说明。

## 证据复用原则

以下历史证据来自仓库已提交的切片记录，本轮没有重演或重新采集其原始临时产物。开发者现场确认、工具直接观察、自动化与 CI 分开列出；进程监听、readiness 或 Nacos 注册不能单独证明业务成功。历史失败保留，不用后续通过覆盖失败轮次。

本轮通过 GitHub REST 读取 #169 正文、评论及父规格；#169 没有额外评论。前置 #162、#165、#166、#168 的状态均为 CLOSED。早期 #165 未完成的记录以实时 Issue 和文末最终验收记录为准。

## #169 验收映射

| 条目 | 已有直接证据与本轮工作 | 状态 / 限制 |
| --- | --- | --- |
| 1. 准备、双 Console 原生命令/日志/停止/HMR，多后端 IDE 调试 | [#162](issue-162-native-console-development.md)实际生成 Client、双前台启动、分别 Ctrl+C、另一应用与 Edge 保留、可见 HMR 标记且无整页刷新；[#163 最终确认](issue-163-native-platform-auth.md#最终验收确认)由开发者确认 Gateway/IAM 断点与修改后重启；[#164](issue-164-native-tenant-access.md)三个 IDE Debug 应用联调 | 复用已通过切片；#162 为 Chromium，Chrome 开发 WSS/HMR 由 [#159](issue-159-four-domain-matrix.md#本次完成记录)补充。不是本轮从空环境重新执行 |
| 2. 五服务配置和说明、个人配置与敏感值边界 | 总入口列出五份模板、主类、classpath、依赖与各服务说明；本轮核实五份个人 YAML 的 Git ignore | 通过文档及已有配置/打包验证；不将本轮忽略检查称为重新打包验证 |
| 3. 真实认证、HTTP/gRPC、业务与 Audit | #163 Platform 登录/刷新；#164 Chrome Tenant 登录、受保护 Context 切换及双向调用；[#165](issue-165-native-entitlement.md)Chrome 创建订阅/初始化、权威用量 0→1、端口变化后的新业务及无健康目标拒绝/恢复；[#166](issue-166-native-audit.md)正式登录 Outbox→Audit、停机积压与 IDE 重启恢复 | 复用各链路的直接证据；#164 的逐跳结论结合代码路径与浏览器结果，并非逐跳网络抓包 |
| 4. Session Slot、Cookie、精确 CORS、CSRF 正负向 | #162 的 32 个探针及双槽位生命周期；#159 Chrome 开发四域 32 个探针、拒绝后恢复、WSS；当前产品代码 CI 的 Chrome Fresh 记录 | 历史通过；本轮未重新运行真实浏览器安全矩阵，不用 Mock 或 TLS 绕过补齐 |
| 5. 日常启动、验证时间/资源、5 分钟与电脑可用性 | #167 的同机代表性局部命令、RSS 和交互探针；#168 完整本机及 CI 记录；见下表 | 部分完成：缺完整首次准备和日常应用启动计时、启动资源观测、IDE 输入/窗口切换观察。保留未采集项，不宣称整体体验达标 |
| 6. 默认文档、完整本机复现与 CI 一致 | 默认入口改为原生流程，旧编排保留专项定位；核对当前 package scripts 和两份 CI workflow | 文档完成；未修改 CI，未运行全量复现 |
| 7. 父规格逐项映射、补组合缺口 | 下表逐项列出父规格 12 条；复用有效切片，新增统一准备顺序、边界和补验步骤 | 映射完成；条目 5 的现场测量仍待补齐 |
| 8. ADR 与父任务边界 | 保留 ADR 0009/0034/0038/0043/0044，兼容范围按 0046 | 未修改 #161、#155、#159 状态，也未据此宣告它们完成 |

## 父规格 #161 的 12 项映射

编号按父 Issue “验收清单”的原顺序，不修改父 Issue 勾选状态。

| 项 | 内容 | 证据 / 剩余项 |
| --- | --- | --- |
| 1 | 首次准备、模板、Git ignore、敏感值与 IDE 参数 | 总入口第 1 节；#162–#166 的准备及模板检查；本轮 Git ignore |
| 2 | 两个 Console 前台运行、日志、停止、不重复生成 | #162 的真实双终端生命周期；本轮原生入口回归 |
| 3 | 首次及契约变化后的正式 Client 生成 | #162 正式生成与过期拒绝；本轮 Client 预检及回归 |
| 4 | 五份 IDE 说明、多个后端 Debug/断点/修改重启 | 五服务索引；#163 开发者最终确认、#164 三服务 Debug、#165/#166 重启操作 |
| 5 | 依赖可配置、文件配置和 Nacos 发现独立 | #163–#166 配置加载测试与真实原生链路；不要求 Nacos Config 可用 |
| 6 | HTTP/gRPC 真实调用、地址变化、无健康目标失败 | #163/#164 与 #165 的实际端口切换、拒绝及恢复；发现异常和非法 metadata 由聚焦回归补充 |
| 7 | 不接管其他实例、不重置数据 | 原生终端/IDE 管理边界；#163–#166 记录由开发者处理冲突和恢复 |
| 8 | 受信 HTTPS、HMR、认证与安全正负向 | #162 生命周期、#159 Chrome 开发安全/HMR、#163/#164/#165 原生真实链路；本轮 doctor 只证明环境检查 |
| 9 | 分层验证与迁移/Nacos/i18n 专项 | 分层验证升级表与贡献指南；未修改这些专项规则 |
| 10 | CI 独有覆盖和失败传播 | 当前 Verify→认证 reusable workflow：JDK 17 全量/workspace/Chromium→Chrome Fresh；独立 Tenant/Audit Fresh、Nacos；#168 负向入口回归及 CI 证据 |
| 11 | 代表性日常耗时、资源、电脑可用性 | #167 有局部命令耗时/RSS/CLI 探针；启动计时和 GUI 观察未采集，不能判定全部完成 |
| 12 | 原生默认文档、旧工具定位、未执行区分 | 本轮文档修改和本报告；全量、Fresh、真实安全矩阵本轮未执行 |

## 耗时、资源和结论边界

以下为历史读数，未作为当前版本新基准。#167 条件：2026-09-10，macOS 27.0、M2 Pro、10 CPU、16 GiB，JDK 17.0.12、Node 24.14.1、pnpm 11.22.0，已有依赖/编译缓存与后台依赖，重型命令串行。

| 范围 | 墙钟 | 单命令最大 RSS | 结果与限制 |
| --- | --- | --- | --- |
| #167 正式 Client 准备 | 4.26 s | 485.36 MiB | PASS，仅生成，不含首次安装/环境准备 |
| #167 Platform 包级 verify | 9.82 s | 587.42 MiB | PASS；对照全工作区 68.25 s / 1255.58 MiB 的覆盖不同，不计算加速比 |
| #167 service-discovery + backend-local | 6.50 s | 447.75 MiB | PASS，11 tests；对照 9.04 s / 384.78 MiB 的编译缓存条件不同，且该模块原本不依赖前端 |
| #167 完整本机 Maven | 403.30 s | 958.92 MiB | PASS，641 tests；超过 5 分钟，包含全部必要覆盖 |
| #168 完整本机 Maven | 414.56 s | 1,214,447,616 bytes | PASS；不同轮次，不能与局部命令或 CI 作同覆盖比较 |
| 首次完整环境准备 / 日常应用启动 | 未采集 | 未采集 | NOT_RUN；不能从进程存活时间推算启动到可用耗时 |
| IDE 输入与窗口切换 | 未观察 | 不适用 | 需要开发者现场观察；CLI 探针不能代替 |

#167 的 20 次启动阶段 CLI 响应为 25.0–73.2 ms，不代表全程、最重负载或 GUI 流畅度。RSS 是操作系统的单命令高水位，不是进程树总和，也不含 Docker VM。#168 的旧 CI 对照累计 job-seconds 5218→4627，最长 job 2106→2199 秒，未证明墙钟提速；它还使用旧兼容矩阵，不能预测当前耗时。

仅已测代表性局部检查低于 5 分钟。完整验收主要耗时为 IAM、OpenAPI/前端聚合、Tenant Access 和 Audit，保留这些必要检查；未承诺温度、节省比例或整机可用性。

## 本轮核实与验证

- PASS：GitHub 实时读取前置 Issue 状态、父规格和 #169；最新列出的 Verify [34747247761](https://github.com/crane199709/saas-forge/actions/runs/34747247761) 为 completed/success，SHA `ed9b49dbb6bad52d9d11b8a3c88df1c617d9f408`。该 SHA 到本轮开始 HEAD 仅有五份文档差异；不声称当前 HEAD 或本轮未提交文档有新 CI。
- PASS：Node 24.14.1、pnpm 11.22.0、PATH 中 Oracle JDK 17.0.12；系统 `/usr/libexec/java_home` 未登记该 JDK，不代表 PATH 的 Java 不可用。
- PASS：五个 Java HTTP 监听 8080–8084 和两个 Node 监听 5173/5174 均存在。该观测不作为业务通过或 IDE 启动耗时证据；本轮没有启停这些应用。
- PASS：正常权限下 `bash scripts/local-https-development.sh doctor` 退出 0，CA/leaf、hosts、系统信任、现有 HTTPS Edge、Docker 与工具链六项正常。
- 环境受限：首次沙箱 doctor 报信任、443、Docker 异常；正常权限重跑全部通过，未修改系统信任或重启 Edge。最初 GitHub GraphQL 连接失败，改 REST 后完成读取。
- PASS：`node consoles/scripts/check-api-client.mjs` 退出 0；五份个人配置 `git check-ignore` 均命中。
- PASS：`node --test consoles/test/native-development.test.mjs scripts/test/https-edge-lifecycle.test.mjs` 正常权限重跑 7/7，0 failed/0 skipped，退出 0。首次沙箱运行 5 passed/2 failed，两项失败均为 Vite 回环监听 EPERM；重跑未停止现有应用，测试自行选择空闲端口。该回归验证入口行为，不代替浏览器 HMR 或人工 IDE 验收。
- PASS：10 份修改/新增文档的 125 个本地 Markdown 链接目标存在；Console 中英文 README 的 Prettier 检查及 `git diff --check` 通过。
- NOT_RUN：完整 Maven/workspace、Fresh Compose、Chrome 开发安全矩阵、Nacos 故障注入与五服务替换；本轮仅修改文档，已有有效切片证据复用，不无条件重跑重型验证。

## 剩余补验

按[总入口第 4 节](../native-local-development.md#4-补验与计时记录)，由开发者在下次自然启停时记录两个 Console 和至少两个 IDE 后端的启动到可用耗时、缓存/依赖条件与资源读数，并在必要局部验证期间观察 IDE 编辑和窗口切换。首次准备未测部分保持未采集，不通过清缓存或重置环境制造新基线。

截至本记录，默认文档和证据映射已完成，现场测量缺口尚在，#169 不应标为全部验收通过。未提交、推送或关闭 Issue；父 #161、#155、#159 保持原状态。


## 开发者现场确认与关闭核对

开发者在收到日常启动计时、活动监视器资源观察、局部验证及 IDE 编辑/窗口切换的具体验证步骤后，明确反馈“均正常，关闭 Issue，核对项勾选”。本次按该现场确认补齐开发体验的定性结论，并按明确指令关闭 #169；上文“剩余补验”保留当时状态，最终结论以本节为准。

- 日常启动、资源观察和验证期间 IDE 操作：开发者确认均正常；本任务未独立观察这些人工操作。
- 未提供本次具体秒数、CPU/RSS/交换量读数，故不新增数值，不宣称本次启动或验证在五分钟内，也不计算性能提升比例。#167/#168 已记录的量化结果及首次准备未采集的限制继续保留。
- #169 第 1–4 项复用上述已通过的原生启动、IDE、真实链路及安全证据；第 5 项采用既有量化基线、本次用户现场确认与显式未采集说明；第 6–8 项由文档切换、父规格映射和范围边界满足。按用户验收决定勾选全部八项。
- 文档实现已提交为 `9074175`；该提交不冒称已有当前 SHA 的远端 CI。此前核实的产品代码 CI 与本轮 7/7 入口回归、链接和格式检查保持各自证据范围。
- 本节只补充验收记录，不触发应用重启、环境重建或重型验证，不自动修改父 #161、#155、#159。
