# 业务服务编码规范

基于 SaaS Forge 平台开发业务服务时必须遵守的规则。本文随脚手架复制进生成的工程，平台升级改动规则时以 CHANGELOG 为准重新复制。

文中术语：

- **租户（Tenant）**：使用 SaaS 的客户组织，业务数据按租户隔离。
- **Realm**：Token 的签发域。租户用户的 Token 属于 Tenant Realm，平台运营人员（Operator）的 Token 属于 Platform Realm，两者互不通用。
- **标准表**：带审计标准列并使用逻辑删除的表，范围见「建表与标准列」。

## 中间件依赖入口

业务工程以 `forge-dependencies` 为父 POM，自动继承 `validate` 阶段的中间件依赖门禁；仅以 BOM `import` 方式管理版本不会继承构建插件。直接声明的依赖即使是 optional 或 test scope，也受检查。

- Redis 连接通过 `spring-boot-starter-data-redis` 接入，租户前缀与安全能力使用 `forge-sdk-tenant`、`forge-sdk-security`。这两个 Forge starter 的 Redis 依赖是 optional，需要 Redis 的服务仍须显式引入 Boot starter。
- RocketMQ 使用 `forge-sdk-mq`；任务调度使用 `forge-sdk-scheduling`。
- Nacos 发现与配置使用 `spring-cloud-starter-alibaba-nacos-discovery`、`spring-cloud-starter-alibaba-nacos-config`。

门禁禁止直接声明 Spring Data Redis、Redisson、Lettuce、Jedis、RocketMQ 与 Nacos 原生客户端、XXL-JOB。上述 starter 带入的传递依赖不受禁令影响。基础设施实现模块只有确需直引时才可设置 `forge.middleware.skip=true`，在属性旁写明原因，并同步平台的中间件豁免清单；业务服务保持默认门禁。

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
  例外：代 Tenant 连接不属于平台的外部数据库（如测试连接、预览查询）时，这类库没有租户列，只能用原生 JDBC。把外部连接收在少数专用类中，在 `ArchitectureTest` 覆盖 `externalJdbcClasses()` 列出这些类并注释理由，见「自动架构检查」。服务自己的库不适用此例外。

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

### 服务凭证与运维凭证

服务端 `forge.ops.token` 统一绑定 `FORGE_OPS_TOKEN`；使用 Manifest SDK 的 `forge.manifest.token` 统一绑定 `FORGE_MANIFEST_TOKEN`。两类凭证分别用于 Platform 调用服务、服务向 IAM 上报，不复用值，不设置静态默认密钥。每个服务独立注入自己的变量，本地也使用独立私有环境文件。

Platform 通过专属 `forge-platform.yaml` 的 `forge.platform.ops.tokens.<spring.application.name>` 集中维护完整运维映射，内置服务和仓库外服务采用同一配置方式。真实凭证只由受控配置源提供，不写入模板或版本库；配置改动后的启动验证必须检查实际 HTTP 认证和上报状态。

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
- 请求体参数加 `@Valid`，在 `record` 字段上用 Bean Validation 注解声明与表结构一致的约束（如 `@NotBlank @Size(max = 200)`）。违反时由 SDK 返回 400 `request.invalid`，`errors` 逐项列出字段，不必等数据库报错，也不在 Controller 中手工校验。

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

浏览器业务调用由前端 SDK 添加 `/api`（如 `/api/orders/orders`），Shell 剥离 `/api` 后再转发网关；网关路由与服务 Controller 保持原路径。服务间、诊断与运维直连网关时不添加 `/api`，新增服务不需要修改 Shell 的前端代理。

网关只做粗粒度判断：要求哪个 Realm 的 Token、能否匿名、是否暴露。服务接入时向平台登记路径前缀（如 `/orders`）与 Realm，前缀下的接口默认要求该 Realm 的 Token，新增接口不改网关。功能权限由服务自己判断，用 `@RequiresPermission` 标注接口。

- 其他匿名接口必须在网关显式列出，同时在服务的安全链中放行，不靠推断。
- 只供同进程或服务间直连调用的接口放在 `/internal/` 下，网关显式拒绝。

## 敏感字段登记

需要脱敏的字段在 Resource Manifest 的 `sensitiveFields` 声明 `path`、`grade` 与可选 `defaultRule`，或在 DTO 字段、record component、getter 上使用 `io.saasforge.sdk.security.SensitiveField` 注解。两种声明合并：服务内字段路径唯一，文件与注解重复声明也会失败；同一 record component 传播到字段和 accessor 的注解只计一次。

`path` 是点分普通标识符，各段满足 `[A-Za-z_][A-Za-z0-9_]{0,62}`，不接受数组索引或表达式；`grade` 只能是 `L1` 公开、`L2` 内部、`L3` 敏感、`L4` 极敏感。分级只决定默认脱敏规则与导出限制，不参与权限判断。`defaultRule` 填平台预置规则编码，省略时使用分级默认规则。

IAM 还会校验字段默认规则存在；保存规则、分级默认与租户选择时再次校验，启动时校验已保存的规则和全部引用。当前 Manifest、分级默认和任一租户的当前字段覆盖引用的规则不能删除。

SDK 在构建期 CLI、启动生成与上报前校验声明，IAM 接收时再使用相同契约校验。新增、移除或调整路径、分级、默认规则（含注解）都必须递增 `forge.manifest.version`，同版本内容不可变；未登记的服务不增加必配项，空声明不改变已生成的旧清单内容。声明负责登记，实际脱敏还需对应执行通道；未声明字段保持原样。

### 在线敏感字段

启用脱敏运行时后，在线 DTO 的 `String` 属性用 `@SensitiveField` 标注并与 IAM 当前 Manifest 中的路径和分级匹配；嵌套对象和集合共用统一 Jackson 序列化器。未标注字段保持原样；缺少提供方、字段、规则或执行失败时整次响应返回 500，null 值也必须先校验规则。一个响应复用一个不可变快照，不因角色或权限码返回原文。

JSON 响应由实际选中的转换器完整序列化到内存缓冲后再发送，保留原有视图、过滤器与声明类型。发送阶段不再查规则；内存占用随响应大小增长，列表应分页。文件下载与流式输出不经过此通道，导出走 `ExcelIo`。含原值的 DTO 覆写 `toString()` 为固定安全文本。确需原文时使用独立未注解 DTO、独立 Permission Code 与审计操作，审计不记录原值。

业务代码通过统一通道脱敏，不自行定义 Jackson serializer、以 `@JsonSerialize` 指定 serializer/converter 或拼接掩码。共享 ArchUnit 检查自定义序列化入口及常见重复星号字面量与截取、替换、重复的组合；普通 `String.replace`、`repeat` 不受限制，静态规则不能证明任意计算安全，仍须验证真实 HTTP 输出。

## 敏感字段导出与日志

导出 record 的敏感列用 `@SensitiveField(path = "customer.phone", grade = "L3")` 对应已登记路径，继续调用 `ExcelIo.export`。未注解的列只按 record component 名精确匹配已登记路径；未匹配列保持原样。一个导出任务只取一次租户生效快照，在创建结果文件前预检全部列（包括零行导出），字典列先翻译显示名再脱敏。已注解但未登记、缺规则、规则执行失败或 L4 字段均以 `io.maskingFailed` 结束任务，不提供结果文件；导出成功与失败的审计都只包含任务与错误码等元数据，不记录自由文本查询摘要。查询摘要、文件名与其他审计详情使用不含敏感原文的说明。导入、模板与错误报告不走导出脱敏。

Logback 的 `%m`、`%msg`、`%message` 与 `%kvp` 由 SDK 在日志初始化前注册。支持两种参数契约：

```java
log.info("customer.phone={} order={}", phone, orderNumber);
log.info("customer={}", SensitiveLogValue.of("customer.phone", phone));
log.atInfo().addKeyValue("customer.phone", phone).log("customer updated");
```

第一种使用完整路径或唯一的字段短名（如 `phone={}`），名称紧邻 `=` 或 `:` 与 `{}`；短名有歧义时丢弃值。第二种使用 `io.saasforge.sdk.security.SensitiveLogValue`，其 `toString()` 始终为 `[redacted]`。结构化 key/value 经 `%kvp` 使用同样的字段名和包装契约。普通参数保持原样。敏感原值均通过上述契约传递；字符串拼接、预先格式化的消息、无名称的位置参数、整个 DTO、异常消息与自定义 JSON 日志 encoder 不在自动识别范围内，调用方须使用安全参数，保留 SDK 的消息及 key/value converter。

日志字段名由应用包注解与本地 Manifest 提前登记，不等待 IAM。日志转换只读取事件捕获的 `tenant_id` MDC 和最长有效一秒的不可变快照，异步刷新 IAM，不在请求或 Logback 线程执行 HTTP。首次日志可能使用 `[redacted]`；配置变更至多一秒收敛。快照过期、缺规则、IAM 不可用、租户上下文缺失或执行失败时立即丢弃原值，并通过非递归的 Logback status 通道告警，不阻断请求。日志这一秒快照不用于导出或在线响应；两者仍按每次执行读取 IAM。关闭应用会释放日志工作线程、快照和策略提供方，停机阶段仍丢弃敏感原值。

## 启动上报与内部状态

引入 `forge-sdk-manifest` 的 Servlet 服务必须配置独立 `forge.ops.token`；该模块带入 Ops 安全链，令牌漏配时启动失败，包括关闭上报的服务。Service Credential（`forge.manifest.token`）只用于向 IAM 上报，不能读取 `/internal/onboarding-status`，也不能与 Ops Token 混用。脚手架已包含两个模块与各自配置项。

SDK 在启动完成后异步上报，失败后按 1、2、4 秒退避重试三次，随后每五分钟最多补报十二次；成功或应用关闭即停止。IAM 不可达不阻塞业务服务启动；`/internal/onboarding-status` 返回本实例上报阶段、成功版本和最近成功/失败时间，网关继续拒绝该内部路径。清单生成错误仍启动失败。

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

## 代码格式

Java 的 `if`、`else`、`for`（含增强 for）、`while`、`do-while` 语句体一律使用大括号 `{}`，即使只有一条语句或为空；`else if` 链沿用常规写法，各分支的语句体仍须加大括号。

业务工程从父 POM `forge-dependencies` 自动继承 Checkstyle `NeedBraces`，在 `validate` 阶段检查 `src/main/java` 与 `src/test/java`，违规即构建失败；`test`、`verify`、`install` 同样先执行检查。仅以 BOM `import` 方式管理版本不会继承构建插件。`./mvnw validate` 可单独运行门禁，不需要启动测试容器；缺少的大括号须补齐后再格式化。

格式交给 Spotless，规则由父 POM `forge-dependencies` 管理，服务 POM 只声明插件坐标，不覆盖规则：

- Java 用 palantir-java-format，并移除未使用的 import。
- `pom.xml` 统一元素顺序与 4 空格缩进。
- `src/{main,test}/resources` 下的 XML 按工程根目录的 `spotless-xml.prefs` 统一缩进，不折行（SQL 换行规则见「Mapper XML 约定」）。该文件随平台版本复制，不在本工程修改。

`./mvnw verify` 最后检查格式，不合规时失败；`./mvnw spotless:apply` 按规则改写。

## 自动架构检查

`forge-architecture` 提供共享 ArchUnit 规则，服务的 `ArchitectureTest` 继承 `ForgeArchitectureTest`，随 Maven `test` / `verify` 执行。测试类放在模块根包，仅扫描生产类，测试夹具不受生产规则约束：

```java
class ArchitectureTest extends ForgeArchitectureTest {}
```

规则覆盖 SQL 注解、字符串 Wrapper、SQL 片段调用及方法引用、JDBC 依赖、服务分层与功能包循环、REST 接口响应信封，以及 `@Scheduled` / `@EnableScheduling`（含组合注解与重复任务容器）。业务定时任务使用 `forge-sdk-scheduling` 的 `@GlobalTask` 或 `@TenantTask`。

连接外部数据库的类可豁免 JDBC 依赖检查，豁免按类全限定名（含其内部类）生效，这些类的 SQL 注解、字符串 Wrapper 与 SQL 片段限制不变：

```java
class ArchitectureTest extends ForgeArchitectureTest {
    @Override
    protected Set<String> externalJdbcClasses() {
        // 代 Tenant 连接其自有数据库，库中没有租户列，MyBatis-Plus 拦截器不适用
        return Set.of("com.example.orders.datasource.service.ExternalJdbcConnector");
    }
}
```

Mapper XML 的 SQL 语义不属于字节码检查范围，仍需代码审查。


### 脱敏运行时配置

使用统一脱敏执行通道时显式设置 `forge.field-masking.enabled=true`，并提供 `forge.field-masking.iam-uri`（IAM 内部 HTTP(S) 地址）与 `forge.field-masking.token`（本服务的 Service Credential）。地址和凭证不设默认值，启用后漏配会启动失败；未启用的旧服务不增加必配项。可复用本服务清单上报使用的 `FORGE_IAM_URI` 与 `FORGE_MANIFEST_TOKEN`，不得在浏览器环境注入这些凭证。

`FieldMaskingPolicyProvider` 经 `/internal/field-masking/{tenantId}` 获取本服务当前租户的不可变策略快照，服务身份由 IAM 从凭证推导。首版不跨请求缓存，平台和租户修改提交后下次读取即生效。生效规则按租户覆盖、字段默认、分级默认依次选择；L4 禁止导出。IAM 不可用或配置破损时显式失败，不能将失败伪装为空规则或原文。一次导出或在线响应复用同一快照，避免处理中途切换规则。
