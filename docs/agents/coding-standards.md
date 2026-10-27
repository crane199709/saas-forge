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
| `filter` | Servlet / Security 过滤器；WebFlux 服务（gateway）的 `WebFilter` 与 `ErrorWebExceptionHandler` |

WebFlux 的 `ErrorWebExceptionHandler` 归入 `filter` 而不是 `controller`：它与 `WebFilter` 同处请求处理链、处理过滤链（含安全过滤链与路由）抛出的异常，不是 `@RestControllerAdvice`，放进 `controller` 会误导读者以为它只覆盖 Controller。

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

## 接口格式

业务接口的成功与失败响应统一使用响应信封（ADR-0015），字段与适用范围见 [统一接口格式规格](../../.scratch/unified-api-envelope/spec.md)。`/oauth2/*`、OIDC 端点、actuator 与静态资源不适用本节。

### 返回值

- Controller 的 public 接口方法返回 `ApiResponse<T>`，用 `ApiResponse.ok(x)`、`ApiResponse.created(x)` 构造，不依赖 Advice 自动包装。这样方法签名就是接口契约，springdoc 的 schema 才能如实反映 `data` 的类型。
- HTTP 状态码如实反映结果：创建返回 201，删除返回 200 且 `data` 为 null，不使用 204（无法携带 `traceId`）。
- 请求体是纯业务 `record`，不套信封；JSON 字段用 camelCase。租户从 Token 取得，请求中不出现 `tenantId` 作为隔离依据。

```json
{
  "code": "ok",
  "httpStatus": 201,
  "data": { "id": "0192f3a1-...", "title": "办公用品", "amount": 128.50 },
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

### 错误码与业务异常

- 错误码为点分字符串 `<领域>.<原因>`，领域小写、原因 lowerCamelCase，如 `order.notFound`、`auth.tenantAccessDenied`。错误码同时是 i18n key。
- 每个功能包在 `service` 层定义自己的 `ErrorCode` 枚举，固定 code 与 HTTP 状态码，如 `OrderErrorCode.NOT_FOUND("order.notFound", 404)`。同一 code 只在一个枚举中定义。
- 业务异常统一抛 `ForgeException(ErrorCode code, Object... args)`，`args` 填充文案占位符；不为业务错误自建异常类型或在 Controller 中手工拼装错误响应。
- 框架级错误码（`request.*`、`auth.unauthenticated`、`auth.forbidden`、`system.*`）由 SDK 映射与提供文案，服务不重复定义。业务错误码的文案放在本服务的 `messages*.properties`。

```json
{
  "code": "order.notFound",
  "httpStatus": 404,
  "errorMessage": "Order 0192f3a1-... not found in current tenant",
  "userTip": "订单不存在或已被删除",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

### `errorMessage` 与 `userTip`

- `errorMessage` 面向开发者：英文，可带 id 等排障上下文，不做国际化，前端不展示。
- `userTip` 面向最终用户：按 `Accept-Language` 以 code 查文案，默认 `zh-CN`，可直接展示；查不到时回退为「操作失败，请稍后重试」，不向用户展示 code。
- 5xx 与未知异常的 `errorMessage` 固定为 `Internal error`，不输出异常信息与堆栈；完整异常写日志，凭 `traceId` 查询。

### 分页与排序

- 分页参数 `page` 从 1 开始；`size` 默认 20、上限 100，超出上限返回 400 `request.invalid`，不静默截断。分页信息放在 `meta.page`，`data` 为当前页数组。
- 排序参数 `sort=字段,asc|desc`，可重复。每个接口声明可排序字段白名单，不在白名单内返回 400 `request.invalid`；排序列名进入 SQL 时遵守「Mapper XML 约定」中 `${}` 的白名单规则。
- 过滤条件用普通 query 参数；复杂到 query 放不下时用 `POST /<资源>/search`，过滤条件放请求体，分页与排序参数仍放 query。

```json
{
  "code": "ok",
  "httpStatus": 200,
  "data": [ { "id": "...", "title": "...", "amount": 10 } ],
  "meta": { "page": { "number": 1, "size": 20, "totalElements": 135, "totalPages": 7 } },
  "traceId": "..."
}
```

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
