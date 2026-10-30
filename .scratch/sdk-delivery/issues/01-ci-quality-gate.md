# 01: CI 质量门

**What to build:** 每个 PR 与 main 推送都运行完整质量门：后端 `verify`（Testcontainers 集成测试、架构规则、Spotless），前端类型检查、单测、格式检查、构建与 mock E2E，加上现有的真实主链路 E2E。各任务互相独立、可以并行，失败时保留报告。见[规格 · CI 质量门](../spec.md#ci-质量门)。

**Blocked by:** 无（实际运行需要先配置仓库远端，这是人工步骤）

**Status:** ready-for-agent

- [ ] 新增后端工作流：JDK 17，缓存 Maven 依赖，运行 `mvnw verify`；runner 提供 Docker，Testcontainers 可用；失败时上传 surefire/failsafe 报告
- [ ] 新增前端工作流：Node 24、pnpm 12.4.2，`pnpm install --frozen-lockfile` 后依次运行 typecheck、test、format:check、build、mock E2E（`pnpm test:e2e`）；失败时上传 Playwright 报告
- [ ] 现有 `Main flow E2E` 工作流保持不变，与上述两个工作流并行
- [ ] 三个工作流都在 PR、main push 与手动触发时运行，都设置超时
- [ ] README「构建与测试」一节说明 CI 跑哪些检查，以及如何在本地复现
- [ ] 验证：配置远端后，三个工作流在一个 PR 上全部通过；故意引入一个格式错误、一个失败单测，对应工作流失败并保留报告（验证后撤销）。远端尚未配置时，用 `act` 或同等方式本地演练，在本工单 Comments 中注明实际的验证方式
