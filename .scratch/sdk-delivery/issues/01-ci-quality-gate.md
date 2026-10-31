# 01: CI 质量门

**What to build:** 每个 PR 与 main 推送都运行完整质量门：后端 `verify`（Testcontainers 集成测试、架构规则、Spotless），前端类型检查、单测、格式检查、构建与 mock E2E，加上现有的真实主链路 E2E。各任务互相独立、可以并行，失败时保留报告。见[规格 · CI 质量门](../spec.md#ci-质量门)。

**Blocked by:** 无

**Status:** resolved

- [x] 新增后端工作流：JDK 17，缓存 Maven 依赖，运行 `mvnw verify`；runner 提供 Docker，Testcontainers 可用；失败时上传 surefire/failsafe 报告
- [x] 新增前端工作流：Node 24、pnpm 12.4.2，`pnpm install --frozen-lockfile` 后依次运行 typecheck、test、format:check、build、mock E2E（`pnpm test:e2e`）；失败时上传 Playwright 报告
- [x] 现有 `Main flow E2E` 工作流保持不变，与上述两个工作流并行
- [x] 三个工作流都在 PR、main push 与手动触发时运行，都设置超时
- [x] README「构建与测试」一节说明 CI 跑哪些检查，以及如何在本地复现
- [x] 验证：配置远端后，三个工作流在一个 PR 上全部通过；故意引入一个格式错误、一个失败单测，对应工作流失败并保留报告（验证后撤销）。远端尚未配置时，用 `act` 或同等方式本地演练，在本工单 Comments 中注明实际的验证方式

## Comments

### 2026-10-30 实现与本地验证

- 新增 `backend-quality.yml`、`frontend-quality.yml`；原 `main-flow-e2e.yml` 未改动。三者独立触发，超时均为 30 分钟。
- mock E2E 沿用 `build:spike` 生成诊断页面和子应用 v2，显式启用 HTML reporter；前端另保留单测、格式检查日志，Bash pipefail 保证 tee 不吞掉失败状态。
- 当前已存在 GitHub origin，但本次未推送、未创建 PR。未安装 act；本地直接执行工作流中的检查命令，并解析三个 YAML 核对触发条件、超时。这不等同于 GitHub runner 验收，远端 artifact 上传尚未验证，最后一项保持未勾选。
- Node 24.14.1 / pnpm 12.4.2：frozen-lockfile 安装、typecheck、全量单测、format:check、build、build:spike 通过；mock E2E 19/19 通过。首次安装与预览服务启动受沙箱限制，放开相应网络/本地监听限制后通过。
- 临时创建格式错误文件并向现有 sub-app-kit 测试注入异常，两个检查均非零退出；日志保存在 `/tmp/forge-ci-format-failure.log`、`/tmp/forge-ci-unit-failure.log`。已撤销注入，恢复后的 sub-app-kit 测试通过。这验证了检查命令的拦截能力，未验证 Actions 的失败上传步骤。
- 首次后端 `verify` 在 IAM 的 Spotless 检查失败；使用项目 `spotless:apply` 修正了 IAM、platform、demo-service 共 9 个 Controller 的现有 import 排序，无业务逻辑变化。
- 格式修正后，JDK 17 下完整 `./mvnw --batch-mode verify` 通过，442 项测试、0 失败、0 错误、0 跳过；含 Testcontainers 与架构规则，Spotless 通过。日志：`/tmp/forge-ci-backend-final.log`。
- 按 code-review 技能串行完成 Standards / Spec 两轴审查：规范问题 0，规格实现问题 0；远端验收待办 1 项。
- `mise exec node@24.14.1 -- deploy/e2e/run.sh` 真实主链路 3/3 通过，脚本已清理独立测试环境；日志：`/tmp/forge-ci-main-flow.log`。
- 实现及本地验证完成，状态转为 `ready-for-human`，等待推送后在 PR 上完成三个工作流与失败 artifact 的远端验收。

### 2026-10-30 远端运行反馈

- 用户确认已成功推送，Backend quality、Frontend quality、Main flow E2E 三个 CI 工作流均运行成功。
- 本条依据用户反馈记录；尚未提供 PR 运行结果及远端故障注入、失败 artifact 上传的验证结果，因此综合验收项保持未勾选，状态仍为 `ready-for-human`。

### 2026-10-31 远端故障注入与恢复验收

- 用户授权创建临时 PR、注入故障并在验证后恢复。基于远端 main `471a91438bad5c158a9db7c56761a1babe35c207` 创建 [PR #1](https://github.com/crane199709/saas-forge-v2/pull/1)，仅增加后端失败测试和前端格式错误 JSON，不修改工作流、业务代码或 main。
- 故障提交 `1ff136a1b5ca73f089edea50719f2f8a98644aab`：[Backend quality](https://github.com/crane199709/saas-forge-v2/actions/runs/41153143189) 因 `CiQualityGateProbeTest.rejectsIntentionalFailure` 的预期断言失败；[Frontend quality](https://github.com/crane199709/saas-forge-v2/actions/runs/41153143045) 的类型检查、单测通过，格式检查准确指出 `ci-quality-gate-probe.json` 并失败。两项均非环境或依赖故障。
- 两个失败任务的 `actions/upload-artifact@v4` 均成功，且下载 API 成功返回 ZIP：`backend-test-reports`（ID `13024035505`，28,345 字节）和 `frontend-quality-reports`（ID `13023788339`，3,977 字节）。保留期 7 天，接口记录的到期日为 2026-11-07。
- 恢复提交 `35918f63db50025c7d932523630d115181667e16` 一次性撤销两个临时文件；GitHub compare 确认与初始 main 的文件差异为空，代码树恢复为 `3e047cb8554a305922cc6490676e6f8a35744380`。
- 同一 PR 的恢复提交上，[Backend quality](https://github.com/crane199709/saas-forge-v2/actions/runs/41153402107)、[Frontend quality](https://github.com/crane199709/saas-forge-v2/actions/runs/41153402011)、[Main flow E2E](https://github.com/crane199709/saas-forge-v2/actions/runs/41153402009) 均 completed/success。PR 已关闭且未合并，main 未修改，工单全部验收项完成，状态改为 resolved。
- 本次验收针对工单 01 已发布到远端 main 的三个工作流，不代表尚未推送的 SDK 模板/镜像等后续改动已在 GitHub 验证。工单 07、09 的仓库与试点安排保持待定。
