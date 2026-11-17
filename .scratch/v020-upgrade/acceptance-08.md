# 工单 08：统一升级质量门与最终坐标验收

日期：2026-11-17。工单：[08](issues/08-upgrade-gate-and-acceptance.md)。最终候选的新建/消费、四个通用场景与两个独立试点最终 receipt 已全部核对通过，本票完成升级发布准备验收。整体代码审查与整改见 [双轴审查记录](review.md)，真正发布仍由后续流程独立执行。08 不等待外部发布后的 09，也未发布或切换环境。

## 候选与源码边界

- 08 从集成 `9efa63ca81df78398c237dcc54ebc46143013ccf` 开始，必需指南/规范指针、archetype/kit 升级提示和统一门禁固定于 `98060f84421179b41b7e96cf123da8e41ba720a8`。
- 用 app 新建独立 managed 版本树 `/Users/liuhuan/.codex/worktrees/v020-release-candidate/saas-forge`；仅版本提交 `9f61c8a0073c82a900abeff45ce21c5600b6afb3` 同步九个版本文件，通过 `version.py check v0.2.0`，工作区干净。集成/08 开发分支继续 `0.2.0-SNAPSHOT`，版本提交不合回开发分支，没有创建发布标签。
- 真实构建目录 `/private/tmp/v020-upgrade/candidate-v020-9f61c8a0`，全部坐标 `0.2.0`，249 个实际 Maven/npm 文件。`candidate.json` SHA-256 为 `ceed5ff8789ffd51f5727e3861c63f2ae90c4750c7cbc387a991b979d98adc94`；已逐文件 verify。不是旧包改名或只改清单。
- 三个原始 tgz SHA-256：SDK `89d39aec9091502911c45b734e6875314bbae7f305076e1a7c6f32a1d75eb041`；UI `0b6b43a30a9e557ff4934544a0f656b6d3ede134355204b8ec11e0a795a9d2a8`；kit `77002bcd4cea8f4bdc7b8545ce6979345d2cb06b54ffd785b03c713d0d19f580`。Maven 逐文件摘要见清单。
- 前阶段候选为 `54115992e00691be57b912e46593709c77c344a1 / 0.2.0-SNAPSHOT`，315 文件，清单摘要 `999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`。01—07 原证据保留其身份，不能将其版本文字换成最终坐标。

## 影响判断与重新验证

`/private/tmp/v020-upgrade/08-candidate-impact/` 保存两候选的完整源码 patch、stat 和 `comparison.json`。对 38 个新旧 Maven JAR 解包逐 entry 比较，没有 `.class` 字节变化；源码中 SDK/平台 Java 行为未变。变更为 POM/Manifest 版本元数据、archetype 的规范及 Agent 指针。npm SDK 改版本常量与声明，UI 仅 package metadata；kit 改版本快照、对应 Vite 版本字符串、生成的 Agent 升级提示和规范副本。没有新增规则、默认 IO/storage、配置或产品功能。

即便行为源码未变，版本、包字节、规范分发及运行包来源均已改变，因此实际重建并重验新建/空消费、服务、Sub-App、storage、IO 和两个试点受影响路径。新候选与快照的摘要差异明确保留，没有将旧快照结果充作本轮执行。

候选固定后的改动 `5231644d` 只涉及 `report.py`、其 CLI 回归与 CI 路径选择：修复诊断中跨行秘密值与不完整 JSON，独立宿主配置精确触发前端/模板门。之后添加验收材料与未随包分发的集中指南，说明离线验证须复用同一 Maven settings 和 mirror。它们不进入 Maven/npm 制品；最终开发 tip 相对候选的完整差异另保存在 `08-final-source-diff/`，包含开发分支保留 SNAPSHOT 的预期版本差异，供真正发布标签构建核对。

审查后的源码整改仅涉及 `deploy/upgrade-smoke/` 下三份测试 fixture 的显式 import，以及 storage 辅助脚本生成 Java 时的 Set/Duration import；均不进入真实 Maven/npm 候选包，没有修改 SDK、平台运行时、迁移或脚手架模板。AI 后续 `5d2dc925` 仅补 FullStackIT 新增两处 TimeUnit 的 import，原完整验收仍来自 `17a934e8`，文档收据提交仍是 `e4b16654`。本轮未重建候选或重跑四场景/两个试点重型测试，而是在新的 `/private/tmp/v020-upgrade/review-fix-checks/` 副本上执行四工程 Spotless、Checkstyle 与 test-compile，并确认受影响 12 个 class 的 `javap -c -p` 输出与原验收一致。原 `08-release-readiness.json`、通用及两个试点 receipt 不覆盖；独立补记见 `review-fix-receipt.json` / `.md`，日志与来源记录见 [双轴审查记录](review.md)。

## 统一入口、失败与 CI

`bash deploy/upgrade-smoke/run.sh <新的仓库外目录> [固定候选]` 为本地和 Template smoke 共用入口。省略候选先调用 `candidate.sh build` 一次（含空消费和新建模板）；指定候选先核对全部摘要。随后一个 `candidate.sh use` 生命周期内顺序执行 service、Sub-App、storage、IO。任一路径失败立即非零退出并保留失败阶段，不以新建成功替代升级成功。

原始候选、生成/升级工程、缓存、日志、HTTP/Flyway 和来源留在 `private/`。CI 只上传 `diagnostics/` 中的来源/结果、脱敏日志及场景截图，不上传 settings/npmrc/缓存、原始 HTML/Trace ZIP 或随机运行配置。导出从本轮 JSON/配置/日志及内存环境注册秘密值，跨行替换并删除凭证上下文；URL 整体隐藏、JWT 移除。不完整 JSON 仅导出安全标记，不覆盖原始失败。

Template smoke 和 release 调用方均仅授予验证 job `contents:read`、`packages:read`，读取 step 的 token 只通过环境传入。没有新增写权限、外部包授权或 push。checkout 完整历史供 IO `git archive` 固定历史。Backend、Frontend、Main flow、Template smoke 四门及 publish/images 依赖保持；升级脚本只触发 Template smoke，升级/独立宿主辅助文件及配置触发实际消费者。两个业务仓库继续独立验收，完整业务测试未迁入平台 CI。远端 CI 未执行，包的实际 Actions 读取授权须由远端结果确认。

TDD 与直接相关检查：CI 三处路径最初红灯，最小映射后 **10/10**；独立宿主配置的无关 Main flow 触发另有红绿记录。诊断 CLI 缺失、无语义随机凭据的跨行 Value 泄漏、不完整 JSON 三片均先红后绿；最终 release 脚本 **18/18**。统一 CLI 无效候选实际退出 1，`08-entry-red/diagnostics/summary.json` 留存 failed 状态。Shell 语法、Python 编译和 `git diff --check` 通过。

## 输入与最终通用结果

| 原工单/实际基线 | 本轮最终坐标结果与证据 |
| --- | --- |
| [01](acceptance-01.md)：候选新建/空消费 | `08-candidate-build.log` exit 0；新建服务 **17/17**；前端安装/格式/lint/style/type/build 和仓库外空消费通过；Shell/独立宿主各 **1/1**。实际 `evidence/package-audit.json` 核对默认无 IO/storage、死信表、Wrapper 可执行、规范原文与升级指针；两原图逐张查看。 |
| [02](acceptance-02.md)：真实 Maven 0.1.0，29 文件闭包；业务 7.4.0-SNAPSHOT、Manifest11 | `08-final/private/business-service`：完整 **18/18**；旧/新实际 JAR 同库 HTTP、权限/租户、原订单保留通过；Flyway **7→9**，原七条完整相同，保留 V1—V7 原文，仅追加默认死信和注释。 |
| [03](acceptance-03.md)：真实 npm 0.1.0 三包与 integrity；业务 1.7.3 | `08-final/private/sub-app`：构建工具/规范差异合入，业务源码独立格式化后逐字节相同，自有采购复核路由、自定义 Vite 和业务版本保留；Shell/独立宿主各 **1/1**，1440/1024 五原图已逐张查看。 |
| [04](acceptance-04.md)：真实发布 storage0.1.0/schema，无 purpose/retention | `08-final/private/storage`：完整最高 HTTP 接缝 **1/1**；Flyway **9→15** 原九条不变，原文件字节和文档引用相同；逻辑删除引用仍归类、未知用途拒绝、PENDING 延续、新上传/用途校验/七天宽限内恢复/权限租户边界通过；再启动历史不变。 |
| [05](acceptance-05.md)：历史未发布 IO 源码 `d9f2b032ebae06000e476ba3fe22f4ff90e88a99`，唯一构建坐标 `0.2.0-io-history-d9f2b032-SNAPSHOT`；不是 IO0.1.0 | `08-final/private/io`：完整最高 HTTP 接缝 **1/1**；Flyway **14→18** 原十四条不变，50 个原 src/main 保留；任务 **4→6**、订单 **2→4**，旧导出/报告字节不变，历史待确认继续且不回填 importModel，新导入/导出与成员/租户边界通过；再启动历史不变。 |
| [06](acceptance-06.md)：Lowcode `29dea28a`，原已接入 0.2.0-SNAPSHOT，阶段升级 `cb974b9d` | 最终分支 `codex/v020-upgrade-lowcode-final`，提交 `28a49fef2dadb20c663db94a56b76cbd5f6ae14c`；后端 **185/185**（升级与图片 HTTP10），Maven31/Boot13/npm77 文件与候选逐字节匹配；前端直接检查与两宿主各 **1/1** 通过，六原图已查看。原代码、V1-V12、自身版本/Manifest2 与用户未提交项保留。最终证据在 `08-lowcode-final/receipt.md` 和 `receipt.json`。 |
| [07](acceptance-07.md)：AI `c5cc07fd`，原已接入 0.2.0-SNAPSHOT，阶段升级 `62f31822` | 原完整验收收据对应 `codex/v020-upgrade-ai-final` 的 `e4b16654887e77af6334ca870cb9596c2bf3a634`，实际完整验收源码 `17a934e8`（至 e4b 仅追加文档）；最新测试规范修复为 `5d2dc9258ba9f88b14ba23acb286062103fba1c2`（`codex/v020-upgrade-ai-review-fix`，交专职 merger 合回 final），只调整新增 TimeUnit 引用并通过格式/编译，不把下列完整测试计数归到该修复提交；Java **19/19**，Maven29/Boot12/npm77 文件匹配；retention **3/3**，前端 **14/14** 与直接检查，全栈Maven **3/3**、Shell/console **3/3**、独立宿主 **2/2**，视觉 **26/26** 均通过，30张基线不变、11原图已查看。187原主文件及当前194文件、九迁移、自身版本/Manifest8保持，不新增IO/storage。证据 `08-ai-final/receipt.md` 与 `receipt.json`。 |

`08-generic-receipt.json` 记录实际 baseline/HTTP JSON 摘要、候选身份、测试数量、历史前缀相同、旧文件/报告字节及截图摘要；`08-final/private/state.json` 的全部四场景 passed，统一进程 exit 0。`08-final/diagnostics` 已用最终导出器重新生成 55 文件并扫描无原始 HTTP/file/JDBC URL、JWT 或缓存/target 目录；实际材料识别了45个秘密值，其中14个无语义长值在50个导出文本中泄漏为0，见 `08-diagnostics-audit.json`。旧发布缓存每次核对原来源和摘要；默认框架排除本机全部 Forge 缓存，不使用本机同版本 install 代替真实输入。

AI 最终复验发现 release 坐标的 Maven origin 约束：消费缓存记录 `forge-candidate`，后续默认 settings 改成 `forge-release` 会报 present but unavailable。业务验证入口新增可选 `AI_MAVEN_SETTINGS`（默认原值，缺文件明确失败），实际复用 `08-ai-final/java/settings.xml`，没有改缓存来源或源码 install。retention与全栈重新验证通过；[集中指南](../../docs/upgrading-v020.md)已回填同一settings/mirror身份要求。

两份独立receipt及运行SHA与候选一致性已由08汇总到 `/private/tmp/v020-upgrade/08-release-readiness.json`；该记录保存原始receipt摘要、来源和关键结果。Lowcode旧130项/38视觉、AI旧56产品项/30Runner项按相关源码和归档内容无行为变化的依据保留，没有误写成本轮重新执行。

## 运行来源、复现与限制

配套三平台 JAR 来自同一 `9f61c8a0`，固定副本 `/private/tmp/v020-upgrade/08-runtime-m2/io/saasforge/<module>/0.2.0/<module>-0.2.0.jar`；源码与每包摘要见 `08-runtime-provenance.json`。同源 Shell/console 使用版本树 `frontend/` 和 frozen 安装；最终两个完整dist已另存 `/private/tmp/v020-upgrade/08-runtime-frontend`，143文件逐个SHA与源码说明在该目录 `provenance.json`，不依赖待归档工作树中的唯一构建物。平台运行副本与业务候选 SDK 消费缓存分开；同源重建产生的 ZIP 时间戳差异不偷换固定运行包，Lowcode 在自己的重型锁内恢复固定副本后核对前后 SHA。

```sh
JAVA_HOME=/path/to/jdk17 bash scripts/release/candidate.sh build /tmp/new-v020-candidate
FORGE_UPGRADE_MAVEN_BASELINE=/path/to/verified-published-maven \
FORGE_UPGRADE_NPM_BASELINE=/path/to/verified-published-npm \
  bash deploy/upgrade-smoke/run.sh /tmp/new-v020-upgrade /tmp/new-v020-candidate
```

构建须来自干净固定版本提交；不提供旧缓存时只读下载，读取凭证留内存。当前机器按 `/private/tmp/v020-upgrade/run-heavy.py` 顺序运行每组完整测试；该本次协调器不进入仓库/CI，没有嵌套等待或操作共享容器。

服务/可选能力的 JWT/JWKS、Redis、Flyway 与 S3 接缝沿用各票边界，MQ/Nacos/远程 Manifest 在固定升级样例关闭；storage 未验证到期扫描/七天后物理删除或其他供应商。Sub-App 运行真实 IAM/Gateway/两宿主，业务旧工程仍保留旧页面样式，不借升级覆盖成新模板。IO 历史输入明确未发布。试点真实外部供应商/Oracle 等边界沿用其独立 receipt。没有因版本变化重跑无关平台全套测试；相关 SDK class 字节一致且本轮所有受影响消费/HTTP/宿主路径实际重验。

集中步骤见 [0.2.0 升级清单](../../docs/upgrading-v020.md)。环境需配套切换平台、网关、Shell、SDK/UI/kit 和业务构建，先安排备份/增量/配置及回退，保持自有代码与迁移，不承诺跨版本混跑。只读发布后交接见 [09](handoff-09.md)：最新认证只读预检于05:30:17至05:30:30 UTC记录22个必要Maven POM、三个npm包均404，远端标签也不存在，来源在 `09-published-check/`；此前未认证401不作未发布证据。结果只代表检查时点，外部发布未执行；真正发布后核对标签/提交/坐标及摘要，再 `publish.sh consume v0.2.0`。本次没有上传、打/移动标签或共享/生产切换，不能声称发布后复验通过。

Lowcode CI 区分实际包消费与源码引导：固定可随集成推送取得的候选直接父实现 `98060f84`，执行既有 `version.py set 0.2.0` 与 `check v0.2.0`；干净export变换后的完整Git tree `506687b9554afc51839dc146156f5bd63e538cfa` 与候选 `9f61c8a0` 完全一致。没有要求远端取得孤立本地版本提交，未运行远端CI或push，证明与失败诊断在Lowcode receipt。
