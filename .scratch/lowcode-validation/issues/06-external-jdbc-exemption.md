# 06: 架构规则需要为外部数据库连接提供 JDBC 豁免

**Status:** resolved

**现象：** `ForgeArchitectureRules.persistence()` 禁止 SDK 包以外的类依赖 `java.sql.Connection`、`Driver`、`DriverManager`、`javax.sql.DataSource` 等 JDBC 类型。`ForgeArchitectureTest.architecture()` 是 `final`，业务服务无法豁免单个类。lowcode-service 要代 Tenant 连接其自有数据库（测试连接、预览查询、Widget 取数），这些库不属于平台、没有 `tenant_id`，MyBatis-Plus 拦截器不适用，只能用原生 JDBC。按现有规则，这类业务写不出来。

**复现：** 在业务服务任一 `service` 类中调用 `DriverManager.getConnection(...)`，执行 `mvn test -Dtest=ArchitectureTest`，`persistence` 规则失败。

**当前绕行（lowcode 工单 02）：** lowcode-service 的 `ArchitectureTest` 不再继承 `ForgeArchitectureTest`，改为逐条执行 `ForgeArchitectureRules` 的共享规则，只对 `io.saasforge.lowcode.datasource.service.ExternalJdbcConnector`（含内部类）跳过 `persistence()`。代价是平台以后新增的共享规则不会自动生效，需要手动同步。

**期望：** 平台提供显式、可审查的豁免方式，例如：

- 在类上标注注解（如 `@ExternalJdbc`，要求写明理由），`persistence()` 的 JDBC 检查跳过带该注解的类；或
- 让 `ForgeArchitectureTest` 提供可覆盖的豁免类名列表。

无论采用哪种方式，豁免都应只放开 JDBC 依赖，SQL 注解、字符串 Wrapper 和 SQL 片段的限制保持不变。规范 `business-service.md` 中「禁止 JDBC」一节应注明这一例外及适用条件：只用于连接非平台的外部数据库。平台提供豁免后，lowcode 恢复继承 `ForgeArchitectureTest`。

## Comments

修复（2026-11-11）：采用可覆盖的豁免类名列表，未用注解——注解须放在生产代码可见的 SDK 中，而 `forge-architecture` 测试夹具引用 `forge-sdk-core` 会与 SDK 模块对 `forge-architecture` 的 test 依赖形成 Maven 反应堆循环。

- `ForgeArchitectureRules.persistence(Set<String>)`：列出的类（含内部类）跳过 JDBC 依赖检查，SQL 注解、字符串 Wrapper、SQL 片段照常检查；`persistence()` 等价于空集合。
- `ForgeArchitectureTest` 新增 `protected Set<String> externalJdbcClasses()`，默认为空。
- `business-service.md`「禁止」与「自动架构检查」两节注明例外与写法，`coding-standards.md`、`CHANGELOG.md` 同步。
- `ForgeArchitectureRulesTest` 覆盖：豁免类及其内部类通过、豁免类内部类用 `QueryWrapper` 仍失败、未列出的类仍失败。

lowcode 待升级到含此改动的平台版本后，`ArchitectureTest` 改回 `extends ForgeArchitectureTest` 并覆盖 `externalJdbcClasses()` 返回 `io.saasforge.lowcode.datasource.service.ExternalJdbcConnector`。
