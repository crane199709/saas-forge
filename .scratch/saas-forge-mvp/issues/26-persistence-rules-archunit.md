# 26: 持久层规范的 ArchUnit 检查

**What to build:** 用 ArchUnit 测试强制 [编码规范](../../../docs/agents/coding-standards.md) 中的 SQL 与持久层规则以及服务模块包结构规则，违规时构建失败，使规则不只依赖 code review。检查对业务服务与 SDK 统一生效，`forge-sdk-*` 中规范列明的豁免除外。

**Blocked by:** 01 工程骨架与版本组合验证

**Status:** ready-for-agent

- [ ] 禁止使用 `@Select`、`@Insert`、`@Update`、`@Delete` 与 `@*Provider` 注解
- [ ] 禁止依赖 `QueryWrapper`、`UpdateWrapper` 等字符串列名 Wrapper
- [ ] 禁止调用 Wrapper 的 SQL 片段方法：`apply`、`last`、`inSql`、`notInSql`、`exists`、`notExists`、`having`、`setSql`、`select(String...)`
- [ ] 禁止在 `forge-sdk-*` 以外依赖 `JdbcTemplate`、`NamedParameterJdbcTemplate` 与 `java.sql` 执行类
- [ ] `io.saasforge.sdk..` 包之间无循环依赖；SDK 不依赖服务模块的包；`forge-sdk-core` 不依赖其他 SDK 模块
- [ ] 服务模块的类位于 `<模块根包>.<功能包>.<白名单层>`（启动类除外）；功能包之间无循环依赖；`controller` 只依赖本功能包的 `service`；跨功能包不依赖对方的 `mapper`
- [ ] 规则以可复用方式提供，各服务模块引入后即生效，demo-service 已接入
- [ ] 每条规则有违规样例测试，证明能拦截
