# CI 完整门禁去重（Issue #168）

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

> 当前阶段范围已由 [ADR 0046](../adr/0046-development-supports-chrome-and-jdk17.md) 调整为桌面 Chrome 当前稳定版与 JDK 17；Chromium 保留日常功能与视觉测试。本文旧矩阵的执行结果属于历史证据，不作为当前多浏览器或 JDK 21 要求。现行复现入口见 [本地验证说明](../local-verification.md)。

依据 #161、#167 与 ADR 0044。实现基点为 `d20360927816a71c7c234e4a941fcc462808f29a`，不改变 #155、#159 的验收条件或状态。

## 覆盖映射与去重依据

以下次数按一次 push 或 pull_request 事件计数；不同事件、发布 tag、手动复现不合并。push 与 PR 的比较基点不同，保留其独立触发，避免削弱契约基线保护。

| 必要证据 | 调整前 | 调整后 | 判断 |
| --- | --- | --- | --- |
| JDK 17 完整 Reactor、前端 workspace、Chromium、JaCoCo | Verify/JDK 17 一次；认证脚本内再次完整 verify | 认证 reusable job 先完整 verify，再执行 `--product` | 相同 checkout、Temurin 17、Node 24.14.1、pnpm 11.22.0 和锁文件；完整通用构建一次，制品在同 job 消费 |
| JDK 21 完整 Reactor、前端 workspace、Chromium、JaCoCo | Verify/JDK 21 | 保留 | JDK 兼容性独有覆盖，不跳过测试或前端 |
| v1 发布基线不可变保护 | JDK 17/21 各执行一次同一 Git diff 检查 | JDK 21 中执行一次 | 同一事件、同一 BASE_SHA/GITHUB_SHA 的纯 Git 检查，不依赖 JDK |
| 普通浏览器消费者兼容 | Verify 的 Chrome、Edge、Firefox、WebKit 四矩阵 | 原样保留 | 独立环境、独立渠道结果与失败状态 |
| 受信 TLS 产品与安全 | 认证 workflow：Firefox、WebKit、Chromium、Chrome、Edge | Verify 调用同一 reusable workflow；五渠道原样保留 | 每个渠道仍使用独立 Fresh Compose 卷；Cookie/CORS/CSRF、双槽位、Remote 证据不变 |
| 产品环境中的消费者兼容 | 认证脚本末尾四渠道 | 原样保留 | 和独立兼容矩阵有不同的 TLS/部署环境变量，不仅因命令相同就删除 |
| Tenant/Audit 生命周期 Fresh Compose | Verify 独立 job | 原样保留 | 业务、Kafka、数据库、故障恢复独有覆盖 |
| Nacos 配置、ACL、恢复 | Verify 独立 job | 原样保留 | 不合并到产品浏览器门禁 |
| 开发四域、原生启动、HMR | 独立开发专项入口与证据 | 原样保留 | Fresh/CI 通过不能代替开发证据，#155/#159 不豁免 |
| Release verify/deploy | 发布 workflow 的 JDK 17/21 与 deploy 生命周期 | 原样保留 | 发布参数与签名环境不同，不在本次合并范围 |
| 静态 Remote 构建 | 完整 workspace 已构建，认证脚本又构建一次 | 删除脚本额外构建 | `verify:workspace → build:workspace → build:static-remote` 已构建并检查冻结 SHA-256；workspace 内其他构建仍保留 |

认证 workflow 移除独立 push/PR 触发，改由 Verify `uses` 调用；保留 `workflow_dispatch`。Verify 增加手动触发。没有引入跨 run artifact、缓存命中或额外调度平台作为正确性前提。JDK 17 的通用构建在设置产品对照根域和受信 TLS 前执行，保留原 Verify 的通用夹具环境；产品阶段继续使用已批准的 Linux `saas.forge.example.com`，无 TLS、安全策略变更。

每事件完整 Maven verify 从 **3 次降为 2 次**（JDK 17 一次、21 一次）；四渠道独立兼容与五渠道 Fresh 产品均保留。源码调用次数不是实际耗时改善。Ubuntu `latest` 与固定 `24.04` 的 runner 标签、下载和缓存也会影响耗时，不据此保证固定加速比。

## 失败传播与制品边界

JDK 17 构建和 Fresh 产品验收是同一 job 的先后步骤，均使用默认成功前置条件；构建失败时不会启动产品步骤。产品失败仍使 reusable job 和调用者失败。JDK 21、独立四浏览器、Tenant/Audit、Nacos 的失败保持各自失败状态；没有 `continue-on-error` 或把 skipped 变成 success 的最终汇总步骤。

`--product` 在创建凭据、证书副本和 Compose 项目前，检查两个 Console HTML、Remote v1 入口及五服务运行 JAR；缺失或同模块多个候选 JAR 必须失败。此检查只证明文件存在，后续验收 Client 构建、镜像启动、Remote 哈希与真实浏览器测试才证明可用性。不会自动清理旧 JAR。新 CI checkout 的制品全部由本 job 的成功完整 verify 提供；本地使用 `--product` 时，调用者需保证源码和构建对应，不能把旧制品复测说成本次完整验证。

JSON 清单仍保留 `scope=--product`，明确本脚本没有再次执行 Maven。完整 CI 证据必须同时引用该 job 的成功 Maven step、产品 step、清理结果和脱敏 artifact；单独 JSON 不能宣称完整 CI。`always()` 只用于保存白名单证据和清理，上传成功不会覆盖前面失败，缺证据仍按 `if-no-files-found: error` 失败。

检查名称变为 `JDK 17 and Console authentication / JDK 17, Fresh Compose and trusted TLS (five browser channels)`；若仓库保护规则固定了旧 `JDK 17` 或认证 job 名称，维护者需更新相应 required check。本次不修改分支保护配置。

## 本机完整复现

准备 JDK 17、Node 24.14.1、pnpm 11.22.0、Docker/Compose 与 Testcontainers 可访问的 daemon；首次在 `consoles` 执行 `pnpm install --frozen-lockfile`，安装 Chromium 测试工具及 Chrome。Maven 需要其依赖仓库网络或已准备的缓存。使用 JDK 17 执行完整 `./mvnw --batch-mode --no-transfer-progress verify`，不加 `backend-local`。

Fresh 前另行按 [四域验收说明](issue-159-four-domain-matrix.md) 准备四个受控域名、受信证书和所有浏览器的正常 TLS 信任，并释放 443；需要 node、pnpm、docker、openssl、ruby。Linux Chrome 验收使用 CI workflow 内明确列出的 hosts、SAN、CA/NSS 步骤。实际 TLS 私钥只保存在受限目录。不得以关闭证书校验获得通过。

```bash
# 完整本机入口自己执行 Maven/workspace，不依赖 CI 或下载制品。
bash scripts/verify-console-authentication-e2e.sh --preflight
bash scripts/verify-console-authentication-e2e.sh

# 已在同一源码成功执行完整 Maven verify 后，可复现 CI 的顺序。
./mvnw --batch-mode --no-transfer-progress verify && \
  bash scripts/verify-console-authentication-e2e.sh --product

# 其他独有覆盖分别执行；环境准备见对应入口和工作流。
pnpm --dir consoles run test:browser:compatibility
bash scripts/verify-tenant-lifecycle-e2e.sh
bash scripts/verify-console-authentication-e2e.sh --development
```

本地与 CI 产品渠道均为 Chrome；Linux CI 复现需 `SF_ACCEPTANCE_TARGET=ci` 及上述信任准备，本地结果不能代替远端 CI 证据。开发入口另需运行中的 Nacos、服务、HTTPS Edge、两个 Vite Console 和受限账号文件。Nacos 的发布、ACL、恢复需要独立依赖环境和对应受限身份，命令仍以 Verify job 为准；不要向共享环境误执行故障恢复测试。

## 实际记录

本节全部 `.scratch/issue-168/**` 引用都是本机临时证据：`.scratch/` 是仓库未跟踪的本地临时目录（不受 Git 跟踪），相关目录与文件现已不存在，其他读者无法从仓库复现。原始记录照原样保留为历史事实；可复现的现行入口是本节列出的命令与 [本地验证说明](../local-verification.md)，证据目录由调用者通过 `SF_BRAND_EVIDENCE_DIRECTORY` 自行指定。

调整前同一源码 `f789f6b215ed740176bd226efb308d9ebd31f178`：

| CI 运行 | 状态 | 实际耗时 |
| --- | --- | --- |
| [Verify 34432705163](https://github.com/crane199709/saas-forge/actions/runs/34432705163) | 全部 job success | JDK 17 job 778 秒，其中 Maven 732 秒；JDK 21 job 782 秒，其中 Maven 735 秒 |
| 同上独立浏览器 | 四渠道 success | Chrome 123、Edge 121、Firefox 86、WebKit 109 秒（job 时间） |
| 同上专项 | success | Tenant fresh 865 秒；Nacos 248 秒（job 时间） |
| [认证 34432705190](https://github.com/crane199709/saas-forge/actions/runs/34432705190) | success | job 2106 秒，完整验收脚本 step 2000 秒；日志 RUN/PASS 时间戳确认其中 Maven 727.471 秒，额外 Remote 构建 0.280 秒 |

来源为 GitHub jobs 的 startedAt/completedAt，精度为秒，不含排队；并行 job 耗时不能相加当作墙钟耗时。原始读取存于 `.scratch/issue-168/before-*-run.json`，认证阶段时间戳分析见 `before-authentication-stage-times.json`（均为本机临时证据，已不存在，不可复现）。这两个旧运行只用于调整前基线，不能作为当前修改的通过证据。

当前验证：

- PASS：真实 `--product` 入口隔离文件系统回归，缺少八类前置文件及歧义 JAR 均在环境初始化前失败；与现有 pnpm/Maven 入口回归合计 16/16、0 skipped。红灯与绿灯记录位于 `.scratch/issue-168/`（本机临时证据，已不存在，不可复现）。
- PASS：修改工作流的 YAML 解析、Bash 语法、`git diff --check`。
- PASS：本机 `./mvnw --batch-mode --no-transfer-progress verify` 退出 0，26 个 Reactor 模块全部 SUCCESS；Maven 641 tests、0 failures、0 errors、0 skipped，前端全工作区类型检查、lint、格式、单元测试、Chromium 与构建门禁通过。macOS 27.0 arm64、JDK 17.0.12、Node 24.14.1、pnpm 11.22.0，复用已有依赖/构建缓存及 Docker；墙钟 414.56 秒，单命令最大 RSS 1,214,447,616 bytes（不是所有子进程或 Docker VM 总和）。原始记录 `.scratch/issue-168/full-verify.log`（本机临时证据，已不存在，不可复现）；本机时间不能与旧 CI 直接相减宣称加速。
- PASS：源码 `2a1d7ae502e7b267068c4739acba8c34c5f3244b` 的 [Verify 34465680489](https://github.com/crane199709/saas-forge/actions/runs/34465680489) 实际完成，8 个必要 job 全部 success；JDK 17/21、独立四浏览器、五渠道 Fresh 产品及 Nacos/Tenant 专项均执行。

### 调整后真实 CI

| 门禁 | 结果 | job / 关键 step 耗时 |
| --- | --- | --- |
| JDK 17 + 五渠道 Fresh 认证 | success | job 2199 秒；完整 Maven 747 秒；产品脚本 1315 秒 |
| JDK 21 | success | job 771 秒；完整 Maven 714 秒 |
| 独立 Chrome / Edge / Firefox / WebKit | 全部 success | job 分别 129 / 136 / 133 / 114 秒 |
| Tenant/Audit Fresh 生命周期 | success | job 888 秒 |
| Nacos 配置、ACL、恢复 | success | job 257 秒 |

调整前 9 个 job，累计 5218 job-seconds；调整后 8 个 job，累计 4627 job-seconds。完整 Maven 从三次变为两次。最长 job 从 2106 秒变为 2199 秒，**本次观测没有证明整体墙钟提速**。基线与本次之间还包含 #163–#167 等实现，且 runner、缓存与网络存在差异；这些是实际观测值，不是相同源码 A/B 测试，不据此计算或承诺优化比例。

[脱敏 artifact 10148655272](https://github.com/crane199709/saas-forge/actions/runs/34465680489/artifacts/10148655272) 对应同一源码；核对副本为 `.scratch/issue-168/ci-evidence/`（本机临时证据，已不存在，不可复现）。`acceptance-run.json` 的 `commit` 与 CI SHA 一致、`dirty=false`、`target=ci`、`scope=--product`、`status=passed`，五个产品渠道、四个产品环境兼容渠道、镜像、TLS 就绪及 Compose reset 全部 passed；最终 passed 由成功清理后的出口记录。独立 Maven step 的 success 与此 JSON 合并构成本次完整 CI 证据，不改写 scope。

| 产品渠道 | 实际版本 | 安全探针 | 未预期错误 / 页面错误 | Remote 资源与策略 |
| --- | --- | --- | --- | --- |
| Chromium | 151.0.7922.34 | 32 | 0 / 0 | passed |
| Chrome | 153.0.8010.36 | 32 | 0 / 0 | passed |
| Edge | 152.0.4191.66 | 32 | 0 / 0 | passed |
| Firefox | 153.0 | 32 | 0 / 0 | passed |
| WebKit | 26.5 | 32 | 0 / 0 | passed |

失败传播证据来自 CI 中执行的真实入口负向回归：JDK 21 日志记录 16/16、0 failures、0 skipped；覆盖默认前端检查返回 41、指定包失败、真实 Maven 遇到不兼容 Node 返回 1，以及缺少八类制品或歧义 JAR 返回 1 且未开始环境初始化。两个 JDK 的该步骤均 success；结合默认步骤成功前置条件、直接执行脚本、无 `continue-on-error` 与无吞错汇总，保留必要失败到门禁的传播。没有故意破坏顶层 workflow 制造失败，也没有把本机回归冒称 CI 日志。

JDK 17/21 CI 完整 Maven 日志各核对 641 tests、0 failures、0 errors、0 skipped。五个产品渠道的脱敏 TAP 各为 33/33、0 failures、0 cancelled、0 skipped；两个 JDK 中的入口回归各为 16/16。原始 job/step 元数据为 `.scratch/issue-168/after-verify-run.json`，受限原始日志为 `ci-jdk21.log` 与 `ci-jdk17-authentication.log`（均位于已不存在的本机临时目录，不可复现）；只公开上述统计和白名单 artifact。

本切片的 CI 验收证据已齐全；开发四域、IDE 与其他专项证据仍独立记录。本次没有修改 #168、父 #161 或其他 Issue 的状态。此后的验收文档提交不冒称已由上述源码运行重新验证。


## 审查

- Standards：无规范阻断项。原 JAR 选择重复已集中到 `runtime_jar()`；镜像函数显式传播选择失败，避免 `stage` 的条件调用抑制 Bash errexit。修改后九项制品回归重新通过。
- Spec：静态覆盖映射无缺失或范围扩张；原 P1 的实际 CI 与失败传播证据已补齐。规格没有要求另造顶层 workflow 失败；真实 CI 内执行的负向入口回归、必要 job 完整成功状态与五渠道 artifact 共同完成验收。

## Tenant Fresh 失败复查（2026-09-11）

后续文档提交 `d759442` 的 [Verify 34469298907](https://github.com/crane199709/saas-forge/actions/runs/34469298907) 为 7/8 job 成功；Tenant Fresh 在第二个撤销索引故障探针失败：预期 HTTP 503，实际 201。Redis 停机探针已通过；失败位于重启 Redis、等待 Ready、人工 `SET Ready=0` 之后。不能用前一次成功结果覆盖这次失败。

IAM 的 `RevocationIndexRecovery.recoverIfNeeded()` 默认每 5 秒检查索引，发现未就绪就重建；`RedisRevocationIndex.rebuild()` 最后写回 Ready=1。原探针未隔离这个写入方，注入到请求之间存在自动恢复窗口。

修复仅作用于验收脚本：在探针子 shell 中临时撤销隔离 Redis 默认用户的 `SET` 权限，再用 `MSET` 写入 Ready=0。IAM 重建使用 `SET`，因此不能在断言前消除故障；IAM 的 JWKS 服务、Redis `GET/MGET` 保持可用。EXIT trap 在正常或异常退出时恢复 `SET` 权限，返回后等待 IAM 自行重建，不再人工置 Ready=1。Redis 停机探针、HTTP 503 与错误码断言、租户数量不变检查及后续恢复和令牌失效验证保持原样。`redis-cli -e` 确保服务端命令错误传播到脚本。

- PASS：真实环境中注入 Ready=0 后等待 6 秒，实测读回 Ready=1，原请求返回 HTTP 201，复现了自动恢复竞争。诊断过程中还确认 Gateway 每次验签都同步读取 IAM JWKS，所以最终方案不能暂停整个 IAM。
- PASS：独立 Redis 8.8.1 对照中，撤销 `SET` 后 IAM 使用的写入命令被拒绝，Ready 可读取且保持 0；恢复权限后可写回 1。该检查不替代完整 HTTP 验收。
- PASS：真实 Bash 探针的编排回归先失败（expected=503 actual=201），修复后五项通过；包含固定竞争恢复时序、禁止写入失败、注入失败、请求失败和响应断言失败，验证失败传播及权限恢复。Compose/HTTP 在此测试中为边界替身，不能替代真实 Fresh 结果。
- PASS：相关 CLI 回归、Bash 语法、两份工作流 YAML 解析、格式与 diff 检查。
- 本次修复提交时，正式 Tenant Fresh 入口及修复提交的完整 CI 复验结果仍待完成；最终通过或失败必须以 [Issue #168](https://github.com/crane199709/saas-forge/issues/168) 后续闭环评论中关联的提交、运行及证据为准，不能引用旧运行替代。
