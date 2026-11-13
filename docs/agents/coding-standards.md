# 编码规范（平台内部）

业务服务必须遵守的规则见 [业务服务编码规范](../standards/business-service.md)，monorepo 内的服务模块（`forge-iam`、`forge-platform`、`forge-gateway`、`examples/*`）与 SDK 同样遵守。本文只记录平台内部的补充规则，不重复通用部分。术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。

格式化交给工具（后端 Spotless：Java 用 palantir-java-format，POM 用 sortPom，资源 XML 用 Eclipse WTP；前端 Prettier），本文不重复格式规则。

平台与 SDK 的生产、测试 Java 代码同样由 `forge-dependencies` 继承 Checkstyle `NeedBraces`，在 `validate` 阶段强制执行通用规范的语句体大括号规则；在 `backend` 执行 `./mvnw validate` 检查整个 reactor。

## SQL 与持久层

通用规则的背景见 ADR-0001、ADR-0006、ADR-0007。

### SDK 豁免

`forge-sdk-*` 模块中必须生成或执行 SQL 的基础设施可以不写在 mapper XML 中：租户拦截器、ABAC SQL 编译器、数据权限拦截器、RLS 会话变量设置（`set_config`）。

### SQL-99 与 PostgreSQL 白名单

- 通用规范的白名单同样适用于 SDK 的 `schema.sql`；上面的 SDK 豁免生成或执行的 SQL，以及 `deploy/` 下的数据库初始化脚本不适用。多库适配暂不实施，原因见 ADR-0020。
- 白名单维护在 [业务服务编码规范](../standards/business-service.md#sql-99-与-postgresql-白名单)，需要新能力时修改该节并按 AGENTS.md 的同步规则发布。
- SDK 的 `schema.sql` 同样带注释，服务复制时一并复制。

## 建表与标准列

背景见 ADR-0001、ADR-0014、ADR-0016，完整定义见 [建表标准列规格](../../.scratch/standard-table-columns/spec.md)。通用规范「标准表的范围」中「其他有业务语义的实体表」在平台内指：

| 表 | 是否带标准列 |
|---|---|
| IAM、platform 中有业务语义的实体表（`tenant`、`identity`、`membership`、`operator` 及后续同类表） | 是 |

`*_by` 在登录流程中同样为 NULL。

### 不让填充配置失效

SDK 模块不得通过 `ConfigurationCustomizer` 调用 `addMapper` 注册 Mapper：此时 MyBatis-Plus 还没把 GlobalConfig 绑定到 Configuration，`addMapper` 会先绑定一份默认 GlobalConfig，之后 `MetaObjectHandler`、逻辑删除等全局配置都不再生效。做法参照 `MqAutoConfiguration`，在 `SqlSessionFactory` 建好之后注册。

## SDK 依赖

SDK 不允许出现循环依赖，模块级与包级都不允许。循环会让各 starter 无法按能力单独引入，也无法单独测试。

- 模块依赖单向：`forge-sdk-core` 是底层，不依赖其他 SDK 模块；SDK 模块不依赖 `forge-iam`、`forge-platform`、`forge-gateway` 等服务模块。
- 包级无环：`io.saasforge.sdk..` 下的包之间不得互相引用成环，同一模块内的包也一样。Maven 只拦截模块级循环，包级循环需要单独检查。
- 出现双向需求时，把共用部分下沉到更底层的模块，或由底层模块定义接口（扩展点），上层模块实现，不要直接反向引用。
- 前端 `@crane199709/sdk`、`@crane199709/ui` 同样适用：包内模块之间不循环 import，共享包不依赖 `apps/` 下的应用。

中间件直引门禁从 `forge-dependencies` 继承，SDK 与平台服务同样适用；支持的 starter 入口见通用规范「中间件依赖入口」。只有原生客户端实现模块按需设置 `forge.middleware.skip=true`，属性旁注释原因，新增依赖时同步 [SDK 依赖与中间件豁免清单](../sdk-dependency-gate.md)。当前仅 MQ 与 Reference Data 豁免，gateway、iam、platform、tenant 等模块仍受门禁约束。

## 服务模块包结构

通用规范的包结构适用于 `forge-iam`、`forge-platform`、`forge-gateway` 与 `examples/*`，例如 `io.saasforge.iam.session.service`；层名白名单需要新增时先修改通用规范与 `ForgeArchitectureRules`。`forge-sdk-*` 按能力一个包组织，包即对外 API，不适用该节。

`filter` 层另放 WebFlux 服务（gateway）的 `WebFilter` 与 `ErrorWebExceptionHandler`。WebFlux 的 `ErrorWebExceptionHandler` 归入 `filter` 而不是 `controller`：它与 `WebFilter` 同处请求处理链、处理过滤链（含安全过滤链与路由）抛出的异常，不是 `@RestControllerAdvice`，放进 `controller` 会误导读者以为它只覆盖 Controller。

## 配置读取

SDK 的配置类放在自动配置旁，以 `@EnableConfigurationProperties` 注册。自定义配置的键前缀为 `forge.*`。

## Java 模型

消息体沿用规格中的事件名（如 `TenantCreated`、`MembershipChanged`）。

## 接口格式

背景见 ADR-0015，信封字段与适用范围见 [统一接口格式规格](../../.scratch/unified-api-envelope/spec.md)。

### 经网关暴露

- 功能权限：Platform Realm 服务按 Operator 的平台角色判断；iam 按自身的管理类权限码 `iam:*`、`platform:*` 自行校验。
- 服务通过 `forge.gateway.services` 声明路径前缀与 Realm（`tenant` / `platform`），网关统一生成安全规则与 OpenAPI 聚合分组，不逐个列出需认证的接口；前缀必须是单个小写路径段（允许数字和连字符），不得占用 `/actuator`、`/v3`、`/swagger-ui`、`/webjars`。列表为空、缺字段、前缀非法或重复时启动失败，修改配置后重启网关生效。
- 业务服务与 iam 的接口默认要求 Tenant Realm Token，`/platform/**` 与 `/iam/platform/**` 要求 Platform Realm Token；新接入的服务只需在配置中声明前缀与 Realm，并添加 Spring Cloud Gateway 路由，无需修改网关代码。
- 已声明服务的 `/v3/api-docs/**` 允许匿名 GET，其余接口默认认证；未声明前缀拒绝访问。IAM 专属匿名与 OAuth2 规则保留在代码中。
- iam 的 OAuth2 端点除授权端点与 JWKS 外同样拒绝。
- iam 中供 Operator 使用、需认证的接口放在 iam 的 `/platform/` 下（网关路径 `/iam/platform/**`），并加入 iam 的 Platform Realm 过滤链；功能权限按 `platform:*` 权限码自行校验。

## 注释

- 领域概念沿用 GLOSSARY.md 的术语。
- 「逻辑不直观时写注释」包括 SDK 的非 public 代码。

必须写文档注释（生产代码）：

- `forge-sdk-*` 中的 public 类、接口，以及这些类型的 public 方法（Javadoc）。它们是业务服务使用的 API。`@Override` 方法的行为与父类约定一致时沿用父类注释，有额外约束时才写。
- 前端 `@crane199709/sdk`、`@crane199709/ui`、`@crane199709/sub-app-kit` 的导出函数与组件 props（TSDoc）。

## 自动架构检查

各服务与 SDK 的 `ArchitectureTest` 都继承 `ForgeArchitectureTest`，测试类放在模块根包，例如 `io.saasforge.demo`。新增模块时，添加 `io.saasforge:forge-architecture` 的 test 依赖，并按通用规范添加 `ArchitectureTest`。

除通用规范列出的规则外，还覆盖 SDK 依赖方向与包循环、IAM Mapper 的 tenantLine 标注；`@Scheduled` 限制只对 SDK 包之外生效。JDBC 基础设施在 SDK 包中豁免，服务只能按类经 `externalJdbcClasses()` 豁免连接外部数据库的类；SQL 注解与 Wrapper 限制对 SDK 同样生效。

字段脱敏的通用规则见 [在线敏感字段](../standards/business-service.md#在线敏感字段)。`fieldMasking()` 对 SDK 实现豁免；正反例同时覆盖自定义 serializer、`@JsonSerialize` 覆盖、手写星号与普通格式化，避免把一般字符串操作当成脱敏。

快速执行全部模块的架构检查：

```sh
cd backend
./mvnw test -Dtest=ArchitectureTest,ForgeArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false
```
