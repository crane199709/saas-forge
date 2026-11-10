# 业务服务编码规范

> 适用平台版本：@project.version@

基于 SaaS Forge 平台开发业务服务时必须遵守的规则。本文随脚手架复制进生成的工程，平台升级改动规则时以 CHANGELOG 为准重新复制。

文中术语：

- **租户（Tenant）**：使用 SaaS 的客户组织，业务数据按租户隔离。
- **Realm**：Token 的签发域。租户用户的 Token 属于 Tenant Realm，平台运营人员（Operator）的 Token 属于 Platform Realm，两者互不通用。
- **标准表**：带审计标准列并使用逻辑删除的表，范围见「建表与标准列」。

## SQL 与持久层

手写 SQL 只能写在 mapper XML 中。这样 SQL 集中在一处便于审查；SDK 通过 MyBatis-Plus 拦截器自动注入租户过滤与数据权限条件，SQL 集中在 mapper 中，拦截器对 SQL 的改写才可预期。SDK 只支持 MyBatis-Plus，不支持 JPA 等其他持久层，用其他持久层得不到自动隔离保障。

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

- 文件放在 `src/main/resources/<功能包>/mapper/` 下，如 `order/mapper/OrderMapper.xml`，文件名与 Mapper 接口同名，`namespace` 为接口全限定名。MyBatis-Plus 默认只扫描 `classpath*:/mapper/**/*.xml`，服务须配置 `mybatis-plus.mapper-locations: classpath*:/*/mapper/**/*.xml`。
- 参数一律用 `#{}`。`${}` 仅允许用于经白名单校验的排序列名，且必须在旁注释说明白名单出处。
- SQL 的换行由作者负责：格式化器只统一缩进、不折行，也不限制行宽，长 SQL 按 `SELECT`/`FROM`/`WHERE` 等子句手工分行。不要靠额外缩进对齐续行，格式化器会抹平。

### 例外

只有以下两类 SQL 可以不写在 mapper XML 中，业务服务没有其他豁免：

- Schema 变更写在 Flyway 迁移 `.sql` 文件中。
- 测试数据通过 `@Sql` 引用 `.sql` 文件准备，不在测试代码中内联 SQL 字符串。

## SQL 写法与表注释

### SQL-99 与 PostgreSQL 白名单

适用于 Flyway 迁移（含从 SDK 复制的 `schema.sql`）、mapper XML、测试与种子 SQL。平台暂不做多库适配，但收敛写法能减少将来换库时要改的 SQL，白名单同时就是一份换库时要单独设计替代方案的清单。

有标准写法时一律用标准写法：

| 改用 | 代替 |
|---|---|
| `CAST(x AS uuid)` | `x::uuid` |
| `CURRENT_TIMESTAMP` | `now()` |
| `TIMESTAMP WITH TIME ZONE` | `timestamptz` |
| `INTERVAL '1' DAY` | `interval '1 day'` |
| `POSITION(a IN b)` | `strpos(b, a)` |

没有等价写法时只能使用以下 PostgreSQL 能力；需要其他能力先向平台提出，进入白名单后再用：

| 类别 | 允许 |
|---|---|
| 元数据 | `COMMENT ON` |
| 索引 | `CREATE INDEX`、部分索引与表达式索引、`USING gist` |
| 租户隔离 | `ENABLE`/`FORCE ROW LEVEL SECURITY`、`CREATE POLICY`、`current_setting`、`set_config` |
| 写入与并发 | `ON CONFLICT`、`RETURNING`、`FOR UPDATE`、`pg_advisory_xact_lock` 与 `hashtext` |
| 分页 | `LIMIT`、`OFFSET` |
| 分区 | 声明式分区及其 PL/pgSQL 维护函数 |
| 类型及其运算 | `uuid`、`jsonb`、`ltree`（含 `<@`、`\|\|`、`subpath`、`nlevel`）、`text`、`text[]`、`bytea`、`jsonb_typeof`、CHECK 中的 `~` 正则 |
| 生成值 | `GENERATED ALWAYS AS ... STORED`、`CREATE SEQUENCE` 与 `nextval`、迁移回填时拼接 UUIDv7 的函数 |

测试专用的探针查询可以使用系统目录（`pg_class`、`obj_description` 等）与 `encode`。

### 表与字段的中文描述

- `CREATE TABLE` 必须在同一迁移文件中用 `COMMENT ON TABLE` 和 `COMMENT ON COLUMN` 写表和全部字段的中文描述，包括 `id`、`created_at` 等通用字段。
- `ADD COLUMN` 必须写新字段的中文描述。字段含义变化时同步更新描述。
- 分区子表只注释父表。从 SDK 复制 `schema.sql` 时连同其中的注释一并复制。
- 服务的 `SchemaCommentTest` 在迁移后检查，表或字段缺描述时失败。SQL-99 写法不做自动检查，由代码审查把关。

```sql
CREATE TABLE order_categories (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL
);
COMMENT ON TABLE order_categories IS '示例订单分类';
COMMENT ON COLUMN order_categories.id IS '分类 ID，UUIDv7';
COMMENT ON COLUMN order_categories.name IS '分类名称';
```

## 建表与标准列

租户业务表带 `tenant_id`：同一服务的所有租户共享一组表，SDK 自动注入租户过滤条件，PostgreSQL 行级安全（RLS）作为第二道防线，防止漏注入时跨租户读写。RLS 默认对表所有者不生效，因此业务表必须同时 `ENABLE` 与 `FORCE ROW LEVEL SECURITY`，执行迁移的账号与应用运行账号分离，两者都不得是超级用户或拥有 `BYPASSRLS`。

主键使用应用侧生成的 UUIDv7：无需在多实例间协调，按时间有序、索引友好，传到前端也没有 long 的精度问题。

### 标准表的范围

| 表 | 是否带标准列 |
|---|---|
| 租户业务表（带 `tenant_id` + RLS，如 `orders`、`order_notifications`） | 是 |
| 其他有业务语义的实体表 | 是 |
| SDK 基础设施表（`forge_outbox`、`forge_consumed_message`） | 否 |
| 纯关联表、日志表、审计分区表 | 否 |

### 标准列

```sql
created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
created_by uuid,
updated_by uuid,
deleted    boolean NOT NULL DEFAULT false
```

- `*_by` 不建外键：操作人可能在其他服务的 schema，或属于 Platform Realm。存当前请求已验证 JWT 的 `sub`，无认证上下文（MQ 消费者、定时任务、Outbox 投递器、租户清理）时为 NULL，表示「系统」。
- 时间列的 `DEFAULT CURRENT_TIMESTAMP` 供迁移回填与 seed SQL 使用；经 MyBatis-Plus 写入时由 SDK 覆盖。
- 不设 `deleted_by`、`deleted_at`：删除时刷新 `updated_at`、`updated_by`，已能回答谁在何时删除。乐观锁 `version` 不属于标准列，需要的表自行添加。
- 实体继承 SDK 的 `AuditableEntity`，不自行声明标准列字段，也不手写赋值：SDK 在 INSERT 时填 `created_*` 与 `updated_*`，UPDATE 时填 `updated_*`，调用方传入的值一律被覆盖。`tenant_id` 仍不在实体中声明。

### 唯一约束

标准表禁止使用 `UNIQUE` 列约束或表约束，唯一性一律用 `WHERE NOT deleted` 的部分唯一索引表达，否则已删除的行仍占着唯一值，删除后无法再创建同名记录：

```sql
CREATE UNIQUE INDEX order_categories_name_key ON order_categories (name) WHERE NOT deleted;
```

### 逻辑删除

- 标准表上的删除一律是逻辑删除（`deleted = true`），不物理删除。`status` 继续表达业务状态，`deleted` 只表示该行对常规查询不可见，两者不混用。
- 租户冷静期结束后的清理同样是逻辑删除：服务实现 SDK 的 `TenantDataCleaner`，由 SDK 在 `CurrentTenant.run(tenantId, ...)` 中调用，用 mapper XML 批量 `UPDATE ... SET deleted = true, updated_at = CURRENT_TIMESTAMP, updated_by = NULL`。
- 不用 RLS 过滤已删除行，RLS 只负责租户隔离。

### 自动处理覆盖不到的写法

以下写法不触发标准列填充或逻辑删除条件，由开发者负责：

1. mapper XML 中手写的 INSERT/UPDATE：自己维护 `updated_at`、`updated_by`，INSERT 还要维护 `created_at`、`created_by`。
2. 不带实体的更新，例如 `update(null, wrapper)`：同上。
3. mapper XML 中手写的查询：每张标准表（包括 JOIN 进来的表）都必须带 `NOT deleted`。
4. 标准表按主键删除用 `deleteById`：实体带逻辑删除与更新填充字段时，它按实体逻辑删除并刷新 `updated_at`、`updated_by`。`delete(wrapper)`、`deleteByMap` 等按条件删除不带实体，按 MyBatis-Plus 实现推断不会触发填充（未单独测试），标准表上不使用；批量删除写在 mapper XML 中，按第 1 条自行维护标准列。

### 不让填充配置失效

服务不得自行声明 `MetaObjectHandler` Bean。SDK 的填充处理器是 `@ConditionalOnMissingBean(MetaObjectHandler.class)`，服务一旦声明自己的处理器，SDK 的就不再注册，标准列填充静默失效，启动与普通查询都不报错。

## 服务包结构

服务的类一律位于 `<模块根包>.<功能包>.<层>`，如 `com.example.demo.order.service`。功能包按领域能力命名（`order`、`category`、`notification`），从第一个类起就分层，不设类数门槛：门槛靠「再多几个再拆」的判断，最终总是平铺。模块根包只放启动类 `XxxApplication`。

层名只用以下白名单，`ArchitectureTest` 会拒绝其他层名：

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

## 配置读取

- 自定义配置用 `@ConfigurationProperties` 的 `record` 绑定，不用 `@Value`。配置类放在所属功能包的 `config` 层。
- 同一个键在一个模块内只由一个配置类绑定，默认值只写在该类的 `@DefaultValue`，使用处注入配置类、不重复默认值。
- 随环境变化的地址、issuer、密钥或证书文件路径不在代码中设默认值：本地值写在服务的 `application.yml`，漏配必须启动失败。框架对空值不报错时（如授权服务器 issuer 为空会改为按请求推断），在配置类的构造器中检查。
- Spring 自带的属性（如 `spring.application.name`）沿用框架的绑定方式。设计不变量（分页上限、与列宽绑定的长度、锁时长）保留为常量，出现按环境调整的需求时再改为配置。

## Java 模型

### 类型引用

- 生产与测试代码中的类型引用使用显式 `import` 加简单类名；仅在同名类型冲突或名称遮蔽时保留全限定名。
- 静态成员通过导入后的类名访问（如 `Objects.requireNonNull`），不为缩短调用新增静态导入。测试中 AssertJ（`assertThat`）、Mockito（`when`、`verify`）与 ArchUnit DSL 例外，按惯例静态导入。
- 反射用的类名字符串、配置文件与生成产物不适用本节。

### 接口模型

- HTTP 接口（含服务间调用）的入参与响应、RocketMQ 消息体与 Outbox 事件禁止使用数据库实体，集合与嵌套字段也不行。使用独立的接口模型，在服务端转换。实体常带密码哈希、`tenant_id`、版本号等字段，直接序列化会泄漏，也会把表结构变成跨服务契约。Service、Mapper 之间在服务内部传递实体不受此限。
- 接口模型命名：请求体用 `XxxRequest`，响应用 `XxxResponse`，列表查询条件用 `XxxQuery`。消息体用过去时的事件名、不加后缀（如 `OrderCreated`、`OrderClosed`）。每个模型职责单一。简单的路径参数与查询参数直接用标量类型。
- 不为分层后缀创建字段与语义完全相同的镜像对象。实体与接口模型职责不同，即使字段暂时相同也保持隔离。

### record 与实体

- 不引入 Lombok。接口模型、消息体与值对象使用 `record`。
- MyBatis-Plus 实体使用普通类，手写 getter/setter。
- `record` 自动生成的 `toString` 包含全部字段：含密码哈希、Token、验证码等敏感字段的 `record` 或实体必须覆写 `toString` 排除这些字段，避免写入日志。

## 接口格式

业务接口的成功与失败响应统一使用响应信封 `{code, httpStatus, data, errorMessage, userTip, traceId, meta, errors}`，null 字段不输出。前端用一套逻辑解析成功与失败，用户报障凭 `traceId` 定位日志。`/oauth2/*`、OIDC 端点、actuator 与静态资源不适用本节。

### 返回值

- Controller 的 public 接口方法返回 `ApiResponse<T>`，用 `ApiResponse.ok(x)`、`ApiResponse.created(x)` 构造，不依赖 Advice 自动包装。这样方法签名就是接口契约，springdoc 的 schema 才能如实反映 `data` 的类型。
- HTTP 状态码如实反映结果：创建返回 201，删除返回 200 且 `data` 为 null，不使用 204（无法携带 `traceId`）。不采用「一律 200 + 业务码」，否则网关、监控与浏览器缓存都无法按状态码区分成败。
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
- 框架级错误码（`request.*`、`auth.unauthenticated`、`auth.forbidden`、`tenant.writeBlocked`、`system.*`）由 SDK 映射与提供文案，服务不重复定义。业务错误码的文案放在本服务的 `messages*.properties`。

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

### 经网关暴露

网关只做粗粒度判断：要求哪个 Realm 的 Token、能否匿名、是否暴露。服务接入时向平台登记路径前缀（如 `/orders`）与 Realm，前缀下的接口默认要求该 Realm 的 Token，新增接口不改网关。功能权限由服务自己判断，用 `@RequiresPermission` 标注接口。

- 其他匿名接口必须在网关显式列出，同时在服务的安全链中放行，不靠推断。
- 只供同进程或服务间直连调用的接口放在 `/internal/` 下，网关显式拒绝。

## 注释

不设注释占比指标，按以下规则写。

### 通用

- 注释与文档注释使用中文，标识符使用英文；领域概念沿用平台与本服务统一的术语。
- 行内注释说明「为什么」：意图、约束、取舍、非直观的原因，不复述代码在做什么。文档注释说明用途与调用约定。
- 所有代码在逻辑不直观时都要写注释。
- 不保留注释掉的代码，需要时从 git 历史找回。
- TODO 必须带工单号，如 `TODO(#05): ...`。

### 必须写文档注释

范围限于生产代码（`src/main`），测试夹具不强制。

- Mapper 接口的每个方法：说明用途，以及是否受数据权限策略（Data Policy）过滤。同一接口的方法结论相同时，可在接口的 Javadoc 中统一说明。

其他业务代码不强制文档注释。Controller 接口说明使用 springdoc 注解，不再重复写 Javadoc。

以上规则没有自动检查，由代码审查把关。

## 自动架构检查

`forge-architecture` 提供共享 ArchUnit 规则，服务的 `ArchitectureTest` 继承 `ForgeArchitectureTest`，随 Maven `test` / `verify` 执行。测试类放在模块根包，仅扫描生产类，测试夹具不受生产规则约束：

```java
class ArchitectureTest extends ForgeArchitectureTest {}
```

规则覆盖 SQL 注解、字符串 Wrapper、SQL 片段调用及方法引用、JDBC 依赖、服务分层与功能包循环、REST 接口响应信封，以及 `@Scheduled` / `@EnableScheduling`（含组合注解与重复任务容器）。业务定时任务使用 `forge-sdk-scheduling` 的 `@GlobalTask` 或 `@TenantTask`。

Mapper XML 的 SQL 语义不属于字节码检查范围，仍需代码审查。
