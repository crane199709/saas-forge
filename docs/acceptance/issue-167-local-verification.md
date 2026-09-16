# Issue #167：按影响范围验证的实测记录

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

## 目标与实现

对应 [Issue #167](https://github.com/crane199709/saas-forge/issues/167)，基准 `3c55589d306dde301febba9fca2b60cef21751f9`。用户确认测试接缝为实际 CLI 进程及其运行结果、退出码。用法及边界升级表见 [本地分层验证](../local-verification.md)。

- `backend-local` 仅跳过 OpenAPI 的前端聚合执行；原默认 `./mvnw verify` 保持完整行为。
- `verify-frontend-workspace.sh --package` 复用指定包现有 `verify`，未传参数仍走全工作区；拒绝无匹配、通配符、缺失脚本，保留失败退出码。
- 无自动 Git diff 路由、测试调度平台或进程托管；CI 只增加入口回归，原 JDK、浏览器、fresh、Nacos 覆盖未删减。

## 机器与依赖条件

2026-09-10，macOS 27.0（26A5425a）、Apple M2 Pro / arm64、10 CPU、16 GiB 内存。Maven Wrapper 3.9.14，Oracle JDK 17.0.12，Node 24.14.1，pnpm 11.22.0，Docker Engine 29.7.2。已有 Maven 缓存和 `consoles/node_modules`（`du -sh` 为 315 MiB），已有 Chromium；未清空缓存、重新安装依赖或重建日常环境。后台已有开发依赖，不能视为独占基准机。

耗时来自 `/usr/bin/time -l` 的 real；RSS 为 macOS 报告的 maximum resident set size，转换为 MiB。它不是全部子进程的同时内存总和，不包含 Docker VM；不将 time 的 peak memory footprint 混作进程树 RSS。下列单次读数不能证明稳定性能改善，也未测冷安装或冷编译。

所有重型命令串行执行。首次前端基线在沙箱内因 `listen EPERM 127.0.0.1` 失败（28.10 秒），随后允许本机回环端口后重跑成功；失败不能算通过，基线表使用成功重跑。没有因此修改测试或安全边界。

## 代表性前后对照

场景为 Platform 自身普通变化及 service-discovery 模块的必要验证；不添加无关业务修改来制造基准。实际流程执行真实类型检查、测试和构建，失败注入另用隔离夹具。

| 场景 | 实际命令（仓库根） | 结果 | real | RSS 高水位 |
| --- | --- | --- | --- | --- |
| 前端调整前全工作区 | `bash scripts/verify-frontend-workspace.sh` | PASS | 68.25 s | 1255.58 MiB |
| 前端调整后单包 | `bash scripts/verify-frontend-workspace.sh --package @saas-forge/platform-console` | PASS，5 tests / 2 files | 9.82 s | 587.42 MiB |
| 后端调整前同模块 | `./mvnw --batch-mode --no-transfer-progress -pl services/service-discovery -am verify` | PASS，11 tests，0 skipped | 9.04 s | 384.78 MiB |
| 后端调整后同模块 | `./mvnw --batch-mode --no-transfer-progress -Pbackend-local -pl services/service-discovery -am verify` | PASS，11 tests，0 skipped | 6.50 s | 447.75 MiB |
| 调整后正式 Client 准备 | `pnpm --dir consoles run generate:api` | PASS | 4.26 s | 485.36 MiB |

前端范围由整个工作区、边界测试、Chromium 和全部构建，缩至 Platform 的 typecheck/lint/format/test/build。其余前端、完整后端、fresh 和兼容性矩阵记为 NOT_RUN；不能用 68.25/9.82 宣称同覆盖加速。普通前端准备只是代码生成，不启动后端。

后端两次范围相同，但调整前重新编译了 2 个测试源文件，调整后报告全部 up-to-date；两次都没有重新编译生产源码。该模块原本不依赖 OpenAPI，因此前端跳过不解释耗时差值，不能据此声称 profile 加速。RSS 在后一次反而更高，保留该读数，不选择性报告。

准备/构建/测试的日志分解：调整前依赖与 Client 已准备，安装/生成阶段 NOT_RUN；调整后因 POM 输入变化单独准备 Client（4.26 秒）。前端调整前 Platform Vitest 为 2.21 秒、Vite 为 339 ms，另有其他包与浏览器；调整后 Platform Vitest 为 2.49 秒、Vite 为 231 ms。后端两个测试类的 runner 时间之和由 3.675 秒变为 2.314 秒。runner 内部时间不包含全部编译、启动与工具开销，且全量工作区内部存在并发，不能相加当作墙钟总时间。

## 实际失败传播与 TDD

- RED：隔离 pnpm workspace 中根 `verify:workspace` 故意退出 41，目标包成功、无关包退出 42；旧入口忽略 `--package`，回归失败，实际返回 41。
- GREEN：新入口只运行目标包并退出 0；选择失败包返回非零；无匹配包、无 `verify`、通配符和错误参数均失败；不传参数仍返回根全量任务的 41。真实 pnpm 子进程执行，不断言拼接的命令文本。
- RED：真实 Maven `exec:exec@verify-consoles` 使用不存在的 `backend-local`，外部 Node 工具边界注入 `v0.0.0`，实际前端入口拒绝，Maven 返回 1。
- GREEN：加入 profile 后相同 Maven 执行返回 0，前端门禁被显式跳过；移除 profile 后真实入口仍拒绝该 Node，Maven 返回 1。没有设置通用 `exec.skip` 或测试跳过参数。
- 前端 CLI 回归 6 项通过；Maven profile 回归同时覆盖本地跳过和默认失败传播。CI 使用串行 Node test 入口，避免同时运行多个 Maven。

## 完整验证与剩余范围

第一次默认完整 Maven 验证按用户要求暂停，以退出码 130 中止（246.35 秒）；它不是通过，也不能用于完整验收结论。#166 关闭并提交到 `a5ad559` 后，重新执行默认完整 Maven 验证，结果为 PASS（退出码 0）：26 个 Reactor 模块全部 SUCCESS，Maven 时间 06:40，外部墙钟 403.30 秒，RSS 高水位 958.92 MiB。实际执行前端全工作区/Chromium、后端单元/集成/契约及 JaCoCo 质量门，`CoverageThresholdIT` 通过。Maven 模块结果汇总为 641 tests、0 failures、0 errors、0 skipped（不含独立前端测试计数）。最终 CLI 回归 7/7 通过，0 skipped。

本地代表性检查通过不代表 JDK 21、Chrome/Edge/Firefox/WebKit、fresh Compose、Nacos 发布/恢复和五服务替换矩阵通过。这些本次未执行的专项覆盖保留在原 CI/验收入口；本 Issue 没有改变认证、迁移、Nacos 或国际化资源，其已有专项要求继续有效。

## 交互与五分钟目标

包级前端连同此次独立准备为 14.08 秒，后端所选模块为 6.50 秒，均低于五分钟。此结论仅适用于本次已有缓存与依赖的代表性范围；没有省略上述两条命令定义的必要检查。完整验收为 403.30 秒，超过五分钟；Reactor 中 IAM 约 1 分 48 秒、OpenAPI（含前端全量）约 1 分 17 秒、Tenant Access 54.16 秒、Audit 51.42 秒，是主要耗时范围。这是保留完整集成/浏览器覆盖的成本，不把它计作日常局部反馈，也不为达标跳过检查。

首次计时未采集前后局部负载期间的探针，因此审查后串行补测。原前端脚本从 `git show 3c55589:scripts/verify-frontend-workspace.sh` 读入临时目录，链接现有 consoles；已确认 `consoles` 与 `services/service-discovery` 在基准至 #166 新增提交之间无差异。后端对照分别使用不带/带 `backend-local` 的同模块命令，不修改业务源码。四条补测命令均退出 0。

补测在每条命令启动后按 0.35 秒间隔执行 5 次 `git status --short`，每次均核对被测进程在探针前后仍在运行，20 次探针均退出 0：

| 运行中的代表性命令 | 5 次命令行响应范围 |
| --- | --- |
| 原前端全工作区入口 | 27.0–73.2 ms |
| 新前端 Platform 包级入口 | 25.0–63.8 ms |
| 原后端同模块 verify | 29.7–42.8 ms |
| backend-local 同模块 verify | 29.7–61.7 ms |

这是启动阶段实际负载下的命令行交互观察，采样不代表全程或最重负载。两次完整 Maven 运行期间另有 0.06 秒的同命令探针，不混入上述局部数据。结果证明采样时命令行仍可响应；用户未提供 IDE 输入/窗口切换观察，因此 GUI 交互记为未观察，不承诺整机峰值或 UI 流畅度。基于这些有限观测与已测耗时，继续采用串行必要验证；没有为追求五分钟删减检查。

## 原始证据

本机日志位于 `.scratch/issue-167/`（不提交日志）：`before-frontend.log`、`before-backend.log`、`after-frontend-prepare.log`、`after-frontend.log`、`after-backend.log`、`full-verify.log`（用户中止）、`full-verify-resumed.log`、`cli-regression.log`，以及补测的 `interaction-probes.json`、`*-probed.log`。以上表格保留可提交的命令、条件和读数；复现方式见日常验证文档。未将既有 Issue #165 文档修改纳入本 Issue。
