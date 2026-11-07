# 工单 01 验收记录

日期：2026-11-07。工作树：`task-run-record`；基线：`a57188e97324e760f5a58885818542fa2bff4ad2`。

## 测试边界

用户已确认的入口 ①：SDK 调度声明与运行查询公开接口，使用真实 Redis（模块 `SharedContainers`，每测试类独立 Redis 库）。仅替换触发时间与外部 IAM 响应；Redis 的物理长度、TTL 与敏感值探针用于验证存储约束。没有测试内部私有方法或模拟记录协作者。

## 红绿过程

1. Global Task 运行中→成功归档及抢锁跳过：初始红灯为 `TaskRunHistory`/`TaskRun` 缺少公开类型；实现后 1 项通过。
2. Tenant Task 计数与安全摘要：首次该测试在目录响应解析失败后被记录为 `SUCCEEDED`；当时 IAM 测试夹具遗漏信封的 `httpStatus`，Jackson 无法把 null 映射为 int，因此这次红灯只证明目录失败未正确记为失败，不能作为租户计数路径的证据。实现结果累计并补齐夹具后，真实租户路径验证了 101 个失败、1 个成功、1 个状态跳过与明细上限 100，2 项通过。
3. 列表保留：红灯为 Redis 物理列表实际长度 `102`、预期 `100`；Lua 原子写入/裁剪后通过。随后 101 次成功不改变问题记录 ID，且将问题列表从 1 小时 TTL 刷回 90 天。
4. 中断恢复与令牌保护：红灯为失锁后实际 `RUNNING`、预期 `INTERRUPTED`；原子读取锁/current、遗留归档和完成令牌保护后，4 项通过。
5. 补充验证已经实现的降级与状态行为：损坏 current 造成启动记录、结束记录和查询均 WARN，任务仍执行且释放锁，移除损坏记录后下一轮正常；参数化覆盖全部租户失败、全部成功、无租户与 IAM 503，新增测试共 9 项通过。

## 命令与结果

在 `backend` 执行：

```sh
./mvnw -o -pl forge-sdk/forge-sdk-scheduling -am install -DskipTests
./mvnw -o -pl forge-sdk/forge-sdk-scheduling spotless:apply spotless:check
./mvnw -o -pl forge-sdk/forge-sdk-scheduling test -Dtest=TaskRunHistoryTest,GlobalTaskExecutionTest,TenantTaskExecutionTest,GlobalTaskRegistrationTest,TenantTaskRegistrationTest
```

- 依赖安装、Spotless 格式检查与 `git diff --check` 均成功。
- 目标测试：45 项，失败 0、错误 0、跳过 0（2026-11-07 18:12:20 +08:00）。其中 `TaskRunHistoryTest` 为 9 项。
- 原 Global/Tenant Task 互斥、续期、代理调用、租户上下文、指标与注册行为回归通过。
- `recent`/`problems` 写入和裁剪位于同一 Lua 脚本；失锁旧执行无法覆盖或清除接替者记录。
- 本票未新增数据库表、迁移、安全边界或生产配置。
- 模块全量与架构检查留给集成分支统一交付执行一次；HTTP 与 Playwright 属于后续工单。

本机原始输出：`/private/tmp/task-run-record-{red1,red2,red3,red4,green1,green2,green3,green4,targeted}.log`；最终机器报告为模块 `target/surefire-reports/`。
