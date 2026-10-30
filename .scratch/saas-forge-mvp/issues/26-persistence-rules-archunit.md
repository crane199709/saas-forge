# 26: 持久层规范的 ArchUnit 检查

**What to build:** 用 ArchUnit 测试强制 [编码规范](../../../docs/agents/coding-standards.md) 中的 SQL 与持久层规则以及服务模块包结构规则，违规时构建失败，使规则不只依赖 code review。检查对业务服务与 SDK 统一生效，`forge-sdk-*` 中规范列明的豁免除外。

**Blocked by:** 01 工程骨架与版本组合验证

**Status:** ready-for-agent

- [x] 禁止使用 `@Select`、`@Insert`、`@Update`、`@Delete` 与 `@*Provider` 注解
- [x] 禁止依赖 `QueryWrapper`、`UpdateWrapper` 等字符串列名 Wrapper
- [x] 禁止调用 Wrapper 的 SQL 片段方法：`apply`、`last`、`inSql`、`notInSql`、`exists`、`notExists`、`having`、`setSql`、`select(String...)`
- [x] 禁止在 `forge-sdk-*` 以外依赖 `JdbcTemplate`、`NamedParameterJdbcTemplate` 与 `java.sql` 执行类
- [x] `io.saasforge.sdk..` 包之间无循环依赖；SDK 不依赖服务模块的包；`forge-sdk-core` 不依赖其他 SDK 模块
- [x] 服务模块的类位于 `<模块根包>.<功能包>.<白名单层>`（启动类除外）；功能包之间无循环依赖；`controller` 只依赖本功能包的 `service`；跨功能包不依赖对方的 `mapper`
- [x] forge-iam 的 `@Mapper` 接口都在类型上标注 `@InterceptorIgnore(tenantLine = "true")`：iam 的表按 ADR-0001 不走租户行拦截，隔离条件写在 SQL 中；漏标的 mapper 在无租户上下文时抛异常，消费事件时会被注入 `tenant_id` 条件（工单 14 引入 forge-sdk-tenant 后的约束）
- [x] 服务模块 `controller` 层 `@RestController` 的公开接口方法返回 `ApiResponse`（见 [unified-api-envelope 规格](../../unified-api-envelope/spec.md)）
- [x] 规则以可复用方式提供，各服务模块引入后即生效，demo-service 已接入
- [x] 每条规则有违规样例测试，证明能拦截


## Comments

2026-10-30 实现完成：
- 新增可复用的 `forge-architecture` 测试支持模块；全部 11 个 SDK 模块及 gateway、iam、platform、demo-service 已接入。
- 42 项规则测试覆盖违规样例、允许的 Lambda 查询/更新及 SDK JDBC 豁免；额外断言 SQL 片段违规原因，避免字符串 Wrapper 规则掩盖 `select` 检查失效。
- 规则实际检出 IAM organization ↔ role 循环，通过角色侧 `OrganizationConditionSource` 与组织侧实现消除，保留原有业务行为。
- Standards 审查无阻断项；Spec 审查发现的注解夹具覆盖及 `select` 独立断言缺口已修复。补强测试检出的泛型擦除漏检也已修复。
- 后端 `./mvnw -o -q verify` 通过：442 项测试，0 失败、0 错误、0 跳过，包含 15 个模块的架构检查及 Spotless 检查。首次沙箱运行因禁止绑定端口失败，获准在沙箱外完整重跑通过。
- 接入说明见 `docs/agents/coding-standards.md` 的“自动架构检查”。
