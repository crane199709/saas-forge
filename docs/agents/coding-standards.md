# 编码规范

格式化交给工具（后端 Spotless + palantir-java-format，前端 Prettier），本文不重复格式规则。术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。

## SQL 与持久层

手写 SQL 只能写在 mapper XML 中。这样 SQL 集中在一处便于审查，租户拦截器与数据权限拦截器对 SQL 的改写也可预期（ADR-0001、ADR-0006、ADR-0007）。

### 允许

- 直接调用 `BaseMapper` 内置的按主键 CRUD：`insert`、`selectById`、`updateById`、`deleteById` 等。
- `LambdaQueryWrapper`、`LambdaUpdateWrapper` 与 `lambdaQuery()` / `lambdaUpdate()` 链式调用，仅限单表条件查询与更新，条件只能由列引用与值参数构成。

### 必须写 XML

以下情况一律写 mapper XML，不得用 Wrapper 拼凑：

- 多表 JOIN、子查询、UNION
- 聚合统计（`GROUP BY` 等，简单 `count` 除外）
- PostgreSQL 特有语法，如 `ltree` 运算符、`jsonb` 操作

### 禁止

- `@Select`、`@Insert`、`@Update`、`@Delete` 及 `@SelectProvider` 等 `@*Provider` 注解。
- `QueryWrapper` / `UpdateWrapper` 等以字符串写列名的 Wrapper。
- 接收 SQL 片段的 Wrapper 方法：`apply`、`last`、`inSql`、`notInSql`、`exists`、`notExists`、`having`、`setSql`、`select("...")`。
- `JdbcTemplate`、`NamedParameterJdbcTemplate` 与原生 JDBC。它们绕过 MyBatis-Plus 拦截器，得不到租户隔离与数据权限保障。

### Mapper XML 约定

- 文件放在 `src/main/resources/<功能包>/mapper/` 下，如 `identity/mapper/IdentityMapper.xml`，文件名与 Mapper 接口同名，`namespace` 为接口全限定名。MyBatis-Plus 默认只扫描 `classpath*:/mapper/**/*.xml`，服务模块须配置 `mybatis-plus.mapper-locations: classpath*:/*/mapper/**/*.xml`。
- 参数一律用 `#{}`。`${}` 仅允许用于经白名单校验的排序列名，且必须在旁注释说明白名单出处。

### 豁免

- `forge-sdk-*` 模块中必须生成或执行 SQL 的基础设施：租户拦截器、ABAC SQL 编译器、数据权限拦截器、RLS 会话变量设置（`set_config`）。业务服务不得自行豁免。
- Schema 变更写在 Flyway 迁移 `.sql` 文件中。
- 测试数据通过 `@Sql` 引用 `.sql` 文件准备，不在测试代码中内联 SQL 字符串。

## SDK 依赖

SDK 不允许出现循环依赖，模块级与包级都不允许。循环会让各 starter 无法按能力单独引入，也无法单独测试。

- 模块依赖单向：`forge-sdk-core` 是底层，不依赖其他 SDK 模块；SDK 模块不依赖 `forge-iam`、`forge-platform`、`forge-gateway` 等服务模块。
- 包级无环：`io.saasforge.sdk..` 下的包之间不得互相引用成环，同一模块内的包也一样。Maven 只拦截模块级循环，包级循环需要单独检查。
- 出现双向需求时，把共用部分下沉到更底层的模块，或由底层模块定义接口（扩展点），上层模块实现，不要直接反向引用。
- 前端 `@forge/sdk`、`@forge/ui` 同样适用：包内模块之间不循环 import，共享包不依赖 `apps/` 下的应用。

## 服务模块包结构

适用于 `forge-iam`、`forge-platform`、`forge-gateway` 与 `examples/*` 等服务模块。`forge-sdk-*` 按能力一个包组织，包即对外 API，不适用本节。

服务模块的类一律位于 `<模块根包>.<功能包>.<层>`，如 `io.saasforge.iam.session.service`。功能包按领域能力命名（`identity`、`session`、`membership`），从第一个类起就分层，不设类数门槛：门槛靠「再多几个再拆」的判断，最终总是平铺。模块根包只放启动类 `XxxApplication`。

层名只用以下白名单，需要新的层名先修改本节：

| 层 | 内容 |
|---|---|
| `controller` | `@RestController` 与 `@RestControllerAdvice` |
| `service` | 业务服务，以及由服务抛出的异常 |
| `mapper` | MyBatis-Plus Mapper 接口 |
| `entity` | MyBatis-Plus 实体 |
| `model` | 接口模型（`XxxRequest`、`XxxResponse`、`XxxQuery`）与 Mapper 查询投影 |
| `config` | `@Configuration` 与配置值对象 |
| `filter` | Servlet / Security 过滤器 |

`entity` 与 `model` 分开，让「实体与接口模型隔离」从包上即可看出。只被单个 Controller 使用的接口模型作为嵌套 `record` 留在该 Controller 内，与接口定义放在一起；被第二个类引用（另一个 Controller、Service 等）时移到 `model`。

依赖约束：

- 功能包之间无循环依赖。
- `controller` 只调用本功能包的 `service`。
- 功能包的数据只经由本包的 `service` 暴露：其他功能包的任何层都注入对方的 `service`，本包的 `config`、`filter`、`service` 可直接使用本包的 `mapper`。

## Java 模型

### 类型引用

- 生产与测试代码中的类型引用使用显式 `import` 加简单类名；仅在同名类型冲突或名称遮蔽时保留全限定名。
- 静态成员通过导入后的类名访问（如 `Objects.requireNonNull`），不为缩短调用新增静态导入。测试中 AssertJ（`assertThat`）、Mockito（`when`、`verify`）与 ArchUnit DSL 例外，按惯例静态导入。
- 反射用的类名字符串、配置文件与生成产物不适用本节。

### 接口模型

- HTTP 接口（含服务间调用）的入参与响应、RocketMQ 消息体与 Outbox 事件禁止使用数据库实体，集合与嵌套字段也不行。使用独立的接口模型，在服务端转换。实体常带密码哈希、`tenant_id`、Membership 版本号等字段，直接序列化会泄漏，也会把表结构变成跨服务契约。Service、Mapper 之间在服务内部传递实体不受此限。
- 接口模型命名：请求体用 `XxxRequest`，响应用 `XxxResponse`，列表查询条件用 `XxxQuery`。消息体沿用规格中的事件名，用过去时、不加后缀（如 `TenantCreated`、`MembershipChanged`）。每个模型职责单一。简单的路径参数与查询参数直接用标量类型。
- 不为分层后缀创建字段与语义完全相同的镜像对象。实体与接口模型职责不同，即使字段暂时相同也保持隔离。

### record 与实体

- 不引入 Lombok。接口模型、消息体与值对象使用 `record`。
- MyBatis-Plus 实体使用普通类，手写 getter/setter。
- `record` 自动生成的 `toString` 包含全部字段：含密码哈希、Token、验证码等敏感字段的 `record` 或实体必须覆写 `toString` 排除这些字段，避免写入日志。

## 注释

不设注释占比指标，按以下规则写。

### 通用

- 注释与文档注释使用中文，标识符使用英文；领域概念沿用 GLOSSARY.md 的术语。
- 注释说明「为什么」：意图、约束、取舍、非直观的原因。不复述代码在做什么。
- 不保留注释掉的代码，需要时从 git 历史找回。
- TODO 必须带工单号，如 `TODO(#05): ...`。

### 必须写文档注释

- `forge-sdk-*` 中所有 public 类、接口与 public 方法（Javadoc）。它们是业务服务使用的 API。
- Mapper 接口的每个方法：说明用途，以及是否受 Data Policy 过滤。
- 前端 `@forge/sdk`、`@forge/ui` 的导出函数与组件 props（TSDoc）。

业务服务与前端应用内部的代码不强制文档注释，逻辑不直观时才写。Controller 接口说明使用 springdoc 注解，不再重复写 Javadoc。
