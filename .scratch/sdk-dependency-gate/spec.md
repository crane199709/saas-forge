# SDK 依赖分层门禁规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准：Forge SDK、Business Service、Service Prefix。本规格不单独记录 ADR，因为门禁是可回退的构建策略。

## 问题陈述

SDK 有 17 个模块，模块之间已有合理的依赖（例如 IO 依赖 ABAC 与 Storage、Storage 依赖 Provisioning）。现有 ArchUnit 规则只拦“SDK 依赖非 SDK”、`core` 依赖其他 SDK 与依赖环，拦不住：

- POM 中声明了却没有 import 的依赖，包括 optional 依赖；
- 新模块把依赖指向不允许的模块；
- 业务工程绕过 starter，直接依赖 Redis、RocketMQ、Nacos 客户端或 XXL-JOB；
- 依赖白名单与文档脱节。

## 解决方案

在构建期增加两层门禁：

- Maven Enforcer 在父 POM 统一禁止业务工程直引中间件，需要中间件的模块或平台服务显式豁免；
- 每个 SDK 模块声明允许的直接依赖白名单，Enforcer 在 `validate` 阶段校验，ArchUnit 继续负责依赖环与包级规则；
- 用负向 fixture 模块证明门禁确实会让构建失败；
- 白名单与豁免清单同步到一张文档表。

## 用户故事

1. 作为 SDK 维护者，我想在添加新依赖时被迫更新白名单，以便依赖变化可审查。
2. 作为 SDK 维护者，我想让未使用或 optional 的声明依赖也被检查，以便不留下隐性耦合。
3. 作为业务服务开发者，我想在直引 Redis 或 RocketMQ 时构建失败，以便被引导使用 starter。
4. 作为平台服务维护者，我想显式声明中间件豁免，以便合理使用底层客户端。
5. 作为贡献者，我想在 `validate` 阶段就得到失败信息，以便不等到测试或运行。
6. 作为架构评审者，我想让依赖环继续由 ArchUnit 拦截，以便不重复实现。
7. 作为新成员，我想在一张表里看到每个模块允许的依赖与豁免原因，以便理解架构。
8. 作为 CI，我想让负向 fixture 证明门禁有效，以便门禁本身不会静默失效。

## 实现决策

- `forge-dependencies` 作为业务工程的父 POM，统一配置 Maven Enforcer 的中间件直引禁令：Spring Data Redis、Redisson、RocketMQ 客户端、Nacos 客户端、XXL-JOB 等。
- 需要中间件的模块或平台服务通过显式属性 `forge.middleware.skip=true` 豁免；豁免必须写注释说明原因。
- SDK 父 POM 配置模块级白名单：每个模块通过属性声明允许的直接依赖，Enforcer 的 `bannedDependencies` 以白名单为 `includes`，拦截白名单外的直接依赖；`searchTransitive=false`，覆盖 optional 与未 import 的声明。
- 不引入层级抽象；分层方向由模块白名单表达，白名单与依赖图文档保持一致。
- ArchUnit 继续保留 `sdkDependencies`、`sdkCycles`、`serviceStructure` 等规则，不重复实现 Enforcer 已覆盖的检查。
- 新增负向 fixture 模块，放在独立 profile 下，故意声明被禁止的依赖；CI 在指定 profile 下构建并断言失败。
- 平台服务（gateway、iam、platform）与实现传输的 SDK 模块（如 mq、tenant）在豁免清单中逐项登记。
- 白名单、豁免清单与理由写入接入文档，新增依赖时同步更新。

## 测试决策

- 好的测试只验证外部行为：构建成功或失败、失败信息是否指向正确的模块与依赖；不直接断言 Enforcer 配置 XML。
- 首选接缝是 Maven 构建本身：一个正向 fixture 通过、一个负向 fixture 失败；这比单测更接近真实消费者。
- ArchUnit 规则沿用现有 `ForgeArchitectureRulesTest` 的行为样例接缝，不新建规则测试框架。
- CI 在 `validate` 阶段运行，负向 fixture 只在专用 profile 下构建，避免拖慢日常构建。
- 参考现有测试：`ForgeArchitectureRulesTest`、`forge-architecture` 的 fixture 类。

## 不在范围

- 重构现有 SDK 依赖图。
- 引入层级表或自动推导依赖方向。
- 重写 CI 流水线。
- 检查第三方库内部的传递依赖。
- 对仓库外业务工程的运行时检查。

## 补充说明

- 门禁的目标是“显式变化”，不是“零依赖”；合理的新依赖通过更新白名单即可加入。
- 豁免是可见的架构债务，新增豁免需要在评审中说明。
- 负向 fixture 是门禁自身的测试，不能因为构建变慢而删除。

## 实现与验收（2026-11-12）

集成分支：`codex/sdk-dependency-gate`，基于 `301237d`；三张工单均已 resolved。主要实现提交：`74a4444`（SDK 白名单）、`9eb1344`（中间件禁令与脚手架同步）、`a6b6954`（持久 fixture、CI 与统一依赖表）。

- 两层 Enforcer 在 validate 检查直接声明，包括 optional/test/provided；传递依赖不受误拦。中间件豁免只跳过自身 execution，SDK 白名单继续生效，ArchUnit 规则保留。
- 根据实际 POM，业务 Boot Redis 与 Spring Cloud Nacos starter 保留；真正直引原生客户端的 MQ、Reference Data 显式豁免并注释，其他 SDK 与平台服务逐项登记为无豁免。
- 统一清单见 [SDK 依赖与中间件门禁](../../docs/sdk-dependency-gate.md)。规范、archetype、Sub-App 脚手架提示、业务接入指南与 CHANGELOG 已同步。

最终集成验证通过：

```sh
cd backend
./mvnw -o --batch-mode --no-transfer-progress validate
cd ..
python3 backend/src/it/sdk-dependency-gate/check.py --offline
```

默认 26 模块全部 SUCCESS；15 项持久行为检查全部 PASS，日志在 `backend/target/sdk-dependency-gate/`。两个门禁退化实验均被回归脚本拒绝，依赖解析失败不会被视为成功。实施期间还完成真实 archetype 生成工程默认成功/直引原生 Redis 失败、相关 POM Spotless、Sub-App 生成测试/类型检查、Prettier、10 项 CI 选择测试。独立 Standards 与 Spec 审查均 0 项发现。

本地验证已完成，远程 CI 未触发；本次为构建策略与文档变更，未重跑无关业务测试。
