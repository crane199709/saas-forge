# Task Run 审查与修复记录

日期：2026-11-07。固定审查基线：`a57188e97324e760f5a58885818542fa2bff4ad2`；审查 tip 与本轮修复起点：`26319f15a102f75e840609bb61cfd96be011a0ec`。

审查分支：`codex/task-run-review`。提交前已执行 `git merge --no-edit codex/task-run-history`，结果为 `Already up to date`。

## Standards

原始审查没有编码规范硬性违规，发现两项 P3 重复代码判断：

1. `TenantTaskExecutor.requireEligible` 与后台执行重复判断 Tenant 是否处于 `trial` 或 `active`。已仅提取私有静态谓词 `isEligible(String)`；请求受理前与后台执行时仍各自查询最新状态，前者同步拒绝，定时整轮跳过不符合资格的 Tenant，单租户重跑则记录失败，原有分支保持不变。
2. `TaskRunner` 定时与手动入口重复执行运行开始、异步提交以及提交失败时释放锁并重抛异常。已提取私有 `submit`，通过 `Supplier<TaskRun>` 保留各自的 `history.start` 参数。定时入口的跳过/锁失败指标、手动入口的 409 拒绝、运行 ID、Operator/目标 Tenant 元数据、异步执行与同一锁令牌的释放逻辑保持不变。

两项发现均已修复。没有新增公共模型、执行器抽象或依赖，也没有扩展重构范围。

## Spec

原始审查对照 `spec.md` 第 2–4 节和三个工单，没有缺失、越界或错误实现。本轮仅合并两处私有重复逻辑，未改变规格和接口。三个已解决工单及规格状态保持原样，最终结项由根集成流程处理。

## 验证

沿用用户已确认的公共测试边界；本轮属于审查阶段的行为保持重构，没有新增私有方法测试或镜像实现的测试。使用现有声明式调度、公开运行记录 API、SDK HTTP、平台 HTTP 与真实 Redis/PostgreSQL 目标测试。

在 `backend` 执行：

```sh
./mvnw -o -pl forge-sdk/forge-sdk-scheduling spotless:apply spotless:check
./mvnw -o -pl forge-sdk/forge-sdk-scheduling -am install -DskipTests
./mvnw -o -pl forge-sdk/forge-sdk-scheduling test -Dtest=TaskRunHistoryTest,TaskOpsHttpTest,TenantTaskExecutionTest,GlobalTaskExecutionTest,ArchitectureTest
./mvnw -o -pl forge-platform test -Dtest=TaskOpsTest,ArchitectureTest
```

- 源码安装：10 个 reactor 模块通过。首次安装因本轮缩短后的 `if` 换行不符合 Spotless 而停止；应用格式后，安装与格式检查均通过。
- 调度目标测试：29 项，失败 0、错误 0、跳过 0，包含运行记录、定时跳过、手动锁冲突、异步响应/元数据、租户资格与上下文、锁续约和架构检查。
- 平台目标测试：6 项，失败 0、错误 0、跳过 0，包含 Task Run HTTP 权限、转发与审计及架构检查。
- Spotless 与 `git diff --check` 通过。
- 根集成已完成的 scheduling 53 项、platform 35 项全量检查本轮未重复；前端未修改，沿用原集成验收记录。

日志：`/private/tmp/task-run-review-install.log`、`/private/tmp/task-run-review-install-final.log`、`/private/tmp/task-run-review-spotless.log`、`/private/tmp/task-run-review-scheduling-targeted.log`、`/private/tmp/task-run-review-platform-targeted.log`。机器报告位于对应模块的 `target/surefire-reports/`。
