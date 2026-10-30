# 08: 模板冒烟 CI 任务

**What to build:** CI 任务在仓库外的空目录中，只用当前提交发布出来的制品生成并验证一个 Business Service 和一个 Sub-App，证明 SDK 在仓库外可用。这是本规格最高层的测试接缝，同时覆盖发布流程、BOM 完整性、archetype、前端脚手架、网关配置驱动和接入清单的可执行部分。见[规格 · Testing Decisions](../spec.md#testing-decisions)。

**Blocked by:** 02 网关按配置声明接入服务, 03 服务数据库准备脚本与平台侧接入清单, 05 后端工程模板与接入指南, 06 前端脚手架可在仓库外运行

**Status:** ready-for-human

- [x] 把当前提交的全部 Maven 制品与 npm 包发布到任务内临时本地仓库（复用 04 的发布流程）
- [x] 在仓库外的空目录用 archetype 生成服务，Maven 只从临时本地仓库和公共仓库解析依赖，不读取 monorepo；运行它的 `verify` 并通过
- [x] 复用主链路 E2E 的环境脚本拉起平台服务；按接入清单用 03 的脚本准备数据库、为新服务登记 Manifest 令牌、在网关配置中声明新前缀与路由
- [x] 启动生成的服务：Manifest 上报成功；经网关调用示例接口，有权限的成员得到 200，没有权限的成员得到 403；`/<前缀>/internal/**` 经网关被拒
- [x] 在仓库外的空目录，用临时 npm registry 中的 `@forge/sub-app-kit` 生成 Sub-App，安装依赖后 lint、类型检查与构建通过
- [x] 作为独立工作流在 PR 与 main 上运行，失败时保留日志；加入发布工作流的质量门
- [x] 本地可用一条命令复现，写入接入指南的「维护者」一节

验证记录：本地临时发布与仓库外后端 verify（14 tests）、前端真实 registry install/lint/typecheck/build 通过（/tmp/ticket08-release2.log）。复用该外部 jar 的真实运行接缝通过：独立 smoke schema、Manifest 菜单、网关 GET 200 / POST 201、未授权 POST 403 与 internal 403；Playwright 1 passed（/tmp/ticket08-runtime.log）。两阶段分别执行；新增组合入口与 GitHub 工作流尚未在 GitHub runner 执行。首次运行暴露 env 隔离丢失 JAVA_HOME，修复后重验通过。MQ/Nacos 不在本接缝覆盖范围。

## Comments

- 2026-10-30 集成验证修复：普通 E2E 模式的空 `TEMPLATE_ARGS` 在 macOS Bash 3.2 + `set -u` 下报 unbound variable，失败清理路径的空 `PIDS` 也存在同类问题。对这两处可空数组使用条件展开，空数组不传入参数，非空数组保留元素边界；Compose 非空数组保持不变。
- 轻量红绿验证使用本机 `/bin/bash` 3.2.57，从实际脚本提取传参语句和 cleanup 循环：修复前空参数与空 PID 两项失败、非空两项通过；修复后四项均通过，包含带空格参数和多个 PID 的 kill/wait 顺序。`/bin/bash -n deploy/e2e/run.sh` 通过。真实普通模式 E2E 由主任务重跑，本条不声明全链路已通过。

### 2026-10-30 集成分支最终验收

- `codex/sdk-delivery-remaining` 完整后端 `./mvnw -B install -Parchetype-it` 通过：447 项现有测试及 14 项生成工程测试，零失败、错误与跳过，包含 Testcontainers、架构与格式检查。日志 `/tmp/sdk-delivery-final-backend.log`。
- 前端完整 171 项测试、`typecheck` 及 `format:check` 通过；日志 `/tmp/sdk-delivery-final-frontend-tests.log`、`/tmp/sdk-delivery-final-typecheck.log`、`/tmp/sdk-delivery-final-format.log`。
- 普通模式主链路 3/3 通过，验证模板模式未改变现有行为；日志 `/tmp/sdk-delivery-final-main-e2e.log`。
- 组合入口 `bash deploy/template-smoke/run.sh` 从临时发布到仓库外生成、14 项后端测试、真实 registry 安装、前端 lint/typecheck/build 和新服务权限联调全部通过；Playwright 1/1，日志 `/tmp/sdk-delivery-final-template-smoke.log`。所有本轮临时服务、容器和测试卷已清理。
- code-review：Standards 0 项；Spec 发现 1 项版本硬编码，已修复并完成红绿验证。集成回归另发现 Bash 3.2 空数组问题，修复后两个 E2E 模式均通过。
- 本地实现与验收完成，转 ready-for-human 供审阅；未推送分支或标签，未在 GitHub runner 执行，真实 MQ/Nacos 和工单 09 的团队试点仍待人工验收。
