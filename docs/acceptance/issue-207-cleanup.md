# #207：旧前端与同仓依赖清理

2026-09-29。实现基线：后端 `4c9b5acbf767f4cdb2bcb74048e4b05afaa69d9f`（master），前端 `f4db8fd804fa3f8afb67cd49d12812f72f77e3c6`（main）。本轮清理后端失效入口并验证两仓独立性，不表示父 #201 或 #183–#189 聚合验收完成。

## 删除前核对

先读取 #207、父 #201 及全部六张前置票的正文与关闭记录，再核对现行工作区、提交祖先关系和证据文件。前置票均为 CLOSED/completed；历史业务结果只在各票声明的范围内有效，不拼成本轮完整业务验收。

| 前置 | 可追溯实现/证据 | 核对结果与限制 |
| --- | --- | --- |
| [web #1](https://github.com/crane199709/saas-forge-web/issues/1) | `82cdb6d`，前端 `docs/acceptance/issue-1-password-setup-20260927.md`；CI `36305729963` | 初始改密/Challenge 真实 Chrome 与自动化分别记录；安全异常及处置按原票保留 |
| [web #6](https://github.com/crane199709/saas-forge-web/issues/6) | `60cdcd9`；CI `36375530218`；关闭评论的逐项证据 | 真实通知重发与丢响应后权威恢复；canContinue/部分跨操作者分支属模拟。原始详细材料仅本地，长期依据为关闭评论，不声称重新执行其完整故障矩阵 |
| [web #7](https://github.com/crane199709/saas-forge-web/issues/7) | `aa9df25`；CI `36329450242`；关闭评论与 `docs/migrations/issue-7-lifecycle-source.md` | 冻结/到期/Membership 失效、双标签与重新认证；不替代 Fresh 聚合 |
| [web #9](https://github.com/crane199709/saas-forge-web/issues/9) | `436fa85`，前端 `docs/acceptance/issue-9-oauth-secret-recovery.md` 及原始 JSON；CI `36393991689` | 真实丢响应、替代凭据消费、跨操作者/自然超时；未覆盖完整服务发现拓扑与 24 小时窗口 |
| [web #10](https://github.com/crane199709/saas-forge-web/issues/10) | `75a812c`，前端 `docs/acceptance/issue-10-remotes.md` 及原始 JSON；CI `36397167485` | 真实 Remote 模块/CSS/图片、匿名请求、来源拒绝和宿主展示；不等于 Fresh 完整安全矩阵 |
| [后端 #206](https://github.com/crane199709/saas-forge/issues/206) | 后端 `4c9b5ac` / 前端 `f4db8fd`；[长期证据](issue-206-independent-verification.md)；CI `36404411941` / `36404390450` | 普通及 Fresh 的登录/上下文/刷新/多标签/退出交接冒烟。重新核验 runId、handoff/报告 SHA-256 与两仓 browser.json 一致；作用域仍为 handoff-smoke-only |

CI 状态来自各票已发布的关闭证据，属于对应历史提交，不冒充本轮新提交 CI。上述前端实现提交与固定 Soybean 上游 `7613bd206cd42001b40e3eafceeb895dcbc277a8` 均是当前前端 HEAD 的祖先。

业务清单：权益/Quota/Plan（web #3）、Tenant（#2）、Subscription（#4）、初始化（#5）、首次改密/密码设置（#1）、通知（#6）、生命周期（#7）、OAuth 管理（#8）与 Secret 恢复（#9）、Remote（#10）均由统一 Console 承接；完整同轮业务主链仍在 #184/#189/#201。

## 本次清理与检查归属

| 旧内容 | 处置及承接 |
| --- | --- |
| `consoles/`、自建 UI、旧页面 Compose/dist 挂载 | 已由迁出提交删除，本次核实跟踪树和现行 scripts/deploy/workflows 无此依赖；不重复删除或恢复双 Console |
| `deploy/docker/console-serve.mjs` | 删除孤立旧双 Console 静态服务：没有现行调用，导入的同目录观察器已不存在。Gateway/TLS/Remote 的有效实现保留在 `deploy/compose/local-https-development/`，前端宿主/冻结夹具由 web #10 承接。历史品牌故障夹具和浏览器安全场景仍从 Git 追溯，待聚合范围见下表 |
| `scripts/run-console-visual-container.sh` | 删除调用已迁出 Vitest 配置的孤立包装器；其中无独立断言。旧测试、PNG 与失败传播要求保留历史；不将前端冒烟截图当成新的完整视觉基线 |
| Compose 布局检查 | 去掉旧 Platform/Tenant 服务豁免及任意 `dist` 路径免检查；现行后端挂载必须真实存在，失败仍非零退出 |
| 默认 OpenAPI 生成/正式 npm Client | 默认生成只写模块 `target/generated-typescript-client`；独立 `typescript-client/` 与 `.github/workflows/api-client.yml` 属后端契约制品交付，必须保留，不能把其中 Node/npm 当作旧 UI 依赖删除 |
| 当前开发/发布/验收及 ADR 引用 | 更新中英文验收部署说明、脚本目录、原生开发、Remote、测试归属；标明 ADR 0027/0038/0039/0051 的历史范围及 0052 的已实现/受控启用区别；历史事实保留 |

| 必要覆盖 | 有效执行归属 | 尚待聚合的范围 |
| --- | --- | --- |
| 正式契约/兼容/生成 Client、Java 服务、迁移、架构、覆盖率 | 后端 Maven `verify`、`backend-verification.yml`、`api-client.yml` | 各自发布/CI 结果单独记录 |
| TLS/精确 CORS/Remote/Gateway/调用方协议 | 后端 `scripts/test/` 和 HTTP 路由契约；前端 `tests/console-protocol.test.ts` | #185 的 Token/Redis/攻击来源完整矩阵，按受控单 Console 拓扑适配 |
| 类型/Lint/Runtime/业务/国际化精确格式化 | 前端 `pnpm run verify`、`tests/locale-format.test.ts` 等 | #201：完整 Session Tabs、ICU/语言资源、生产错误边界；不以部分测试覆盖全部旧断言 |
| 视觉/主题/品牌/键盘/焦点/无障碍 | 前端各业务票真实证据、`scripts/verify-browser.mjs` 的展示与焦点冒烟 | #103/#201：布局重排、正式像素基线及自动无障碍完整矩阵；缺失不能永久 skip 或更新快照掩盖 |
| Remote 匿名静态交付与宿主消费 | 后端 `remote-static-delivery.test.mjs`；前端冻结制品/`remote-browser-checks.mjs`、web #10 | #183/#185/#189：完整 Fresh 同轮来源隔离与安全故障 |
| Fresh/环境隔离/服务生命周期 | 后端 `verify.yml` 保留 Tenant fresh-volume 与 Nacos ACL/故障恢复；`acceptance-handoff.mjs` 负责独立交接 | #183–#189：真实邮件/Audit/业务主链、生命周期英文、服务发现/Gateway/Scope/窗口、故障恢复及清理同轮汇总 |

未调整 CI、不新增 skip/continue-on-error，不删除独有断言或历史基线。原流程中尚未迁入的检查明确保持未完成，入口清理不降低这些检查的完成条件。

## T1 兼容、历史与数据

沿用 #202/#204 的兼容/退役边界：未切换环境继续支持既有 v1；统一协议默认关闭。启用会导致旧浏览器会话退役，须按[#204 受控切换](issue-204-unified-console.md#受控切换)处理所有实例、签发阻断和撤销确认。本次没有改动契约、安全开关、来源白名单、旧 Token 验证或外部消费者，不把旧 UI 删除当作退役授权。已切换环境不得自动重开旧登录。

原始业务历史在后端 Git，迁入历史来源在前端 `docs/migrations/`，相关提交可按[历史追溯](console-history-reproduction.md)重建；未强推、重写 main 或复制快照覆盖上游。数据库、Flyway 与兼容基线不变。开始时已有 `.dockerignore` 修改不在本次提交范围内。

## 本轮验证

前端独立副本来自固定 `f4db8fd`，没有后端相邻仓库，不复制现有 node_modules 或个人配置。安装使用仓库锁定的 pnpm 11.22.0；随后类型、Lint、测试、构建和 dev 期间，临时 PATH 将 Java/Maven 设为退出 99 的工具替身，未发生调用。开发监听只验证启动和 HTML 返回，不冒充真实 Chrome/Gateway 业务验收。

| 检查 | 本轮结果 |
| --- | --- |
| 前端干净 `pnpm install --frozen-lockfile` | 通过，Node 24.14.1 / pnpm 11.22.0，正式 Client 0.4.0 |
| 前端类型 / 只读 Lint / 全部 Node 测试 | 通过，216 项，0 失败 / 0 跳过 |
| 前端生产构建及 postbuild | 通过；初次 postbuild 的 tsx IPC 被沙箱拒绝，正常本机权限重跑构建后通过 |
| 前端 dev | 缺少 SF_CONSOLE_ORIGIN 时拒绝；注入非敏感受信域名配置后，临时空闲回环端口返回 200 和 sf-build 身份，随后只停止本次创建的进程 |
| 后端工具全套 `node --test --test-concurrency=1 scripts/test/*.test.mjs` | 62 项通过，0 失败 / 0 跳过；含真实 Maven 默认 OpenAPI verify 在不可用 Node 替身下成功 |
| `python3 scripts/validate-compose-layout.py` | 通过：6 个独立应用、4 个场景、挂载、网络/卷隔离和迁移门禁 |
| JDK 17 默认完整 `./mvnw --batch-mode --no-transfer-progress verify` | 首轮失败：713 项中 1 项 RepositoryStandardsTest 扫入已有 `.scratch/issue-206/history-source`，把历史 Mapper 判为不属于当前服务；0 错误 / 0 跳过，所有前序模块通过。未改动该副本或测试逻辑 |
| 仓库规范单项组复核 | 用 HEAD 跟踪源码加本次差异形成临时源码快照，经现有 `repositoryRoot` 参数指定；19 项全部通过，原工作区不改动 |
| 完整验证复核 | `./mvnw --batch-mode --no-transfer-progress -DrepositoryRoot=<临时源码快照绝对路径> verify` 通过：724 项，0 失败 / 0 错误 / 0 跳过；包含契约、真实服务集成、制品、数据库边界与覆盖率门禁 |
| 历史/证据/文档 | Soybean 上游及前置提交祖先关系、#206 普通/Fresh 三份证据摘要和两仓浏览器字节一致；变更 Markdown 本地链接与 `git diff --check` 通过 |

完整复核的源码审计快照只含版本控制文件和本次差异，制品审计链接本轮实际构建的 target；Java 编译、服务集成测试仍在原工作区执行。用 `-DrepositoryRoot=<临时源码快照绝对路径>` 隔离无关历史副本，不使用 skip、更改断言或关闭门禁。默认命令在保留该历史副本的工作区仍可能出现同一环境污染，不能把指定源码快照的复核说成默认命令已直接通过。初次前端 verify 还遇到本机 pnpm 12 被嵌套调用的版本冲突，固定到 11.22.0 后各阶段通过，未改变锁文件或放宽版本检查。

本轮未执行真实 Chrome 业务重验、完整 Fresh/故障注入、远端新提交 CI 或任何产品发布；前置真实业务与 Fresh 冒烟仅引用已核对的原轮次。后续必须聚合：#184 邮件/密码设置/Audit 同轮主链；#185 完整安全/Redis 失败关闭；#186 生命周期英文；#187 服务发现/Gateway/Scope/24 小时窗口；#188 恢复消费与 #189 同轮汇总；#103/#201 完整视觉、布局、自动无障碍和未迁国际化/会话负例。历史与局部证据不能合并冒充这些场景本轮已完成。

## 审查

Standards：删除入口无现行调用或独有断言；既有前端能力误述已纠正，复核无剩余问题。Spec：现行 UI、测试、认证等文档仍指旧路径/双 Console/v2 未实施的问题已修复，历史范围与待聚合归属明确，复核无剩余问题。两轴均为提交前独立审查，不代替上述验证。
