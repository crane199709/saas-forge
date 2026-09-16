# saas-forge SDK 设计

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

## 定位与版本

Java SDK 与 Spring Boot Starter 是 Java 业务服务接入平台的正式集成层。首版使用 BOM 统一锁定已交付模块的版本；首次正式发布后的破坏性 API 仅在主版本升级时引入。

```text
saas-forge-java
├── saas-forge-bom
├── saas-forge-sdk-core                 # 首版公开
├── saas-forge-sdk-auth                 # 首版公开
├── saas-forge-sdk-tenant               # 首版公开
├── saas-forge-sdk-permission           # 后续占位，不发布
├── saas-forge-sdk-feature              # 后续占位，不发布
├── saas-forge-sdk-quota                # 后续占位，不发布
├── saas-forge-sdk-audit                # 后续占位，不发布
└── saas-forge-spring-boot-starter      # 首版公开
```

业务服务通过 `io.github.crane199709:saas-forge-spring-boot-starter` 接入。除 Java 外的 SDK 不属于首期范围；Maven 坐标与 Java 包名分离的原因见 [ADR 0012](adr/0012-maven-coordinates-use-github-namespace.md)。

## 首版发布面

`saas-forge-bom` 只管理 `saas-forge-sdk-core`、`saas-forge-sdk-auth`、`saas-forge-sdk-tenant` 与 `saas-forge-spring-boot-starter`。Starter 在公开 SDK 中也只传递这三个 SDK；Permission、Feature、Quota 与 Audit 模块可以继续保留在 Reactor 中，但不进入 BOM、不成为 Starter 依赖，并显式跳过 Maven Central 发布。

Starter 还依赖可发布的 `saas-forge-http-route-catalog` 支撑制品，以便与 Gateway 消费同一份不可变路由和认证分类。它不是 BOM 管理的消费者 SDK，也不应由业务应用直接声明。

每个首版公开 SDK 与 Starter 的允许 package 和公共类型记录在 [`saas-forge-sdk/public-api-allowlist.json`](../saas-forge-sdk/public-api-allowlist.json)。构建会对照打包后的 JAR 检查该清单，并检查公共签名、JAR 内容、实现类型引用和传递依赖；新增公共类型或 package 必须显式修改清单。门禁拒绝平台内部 Protobuf、gRPC、持久化记录、数据库实体、MyBatis、Repository、迁移实现以及 Cookie、`Origin`、Fetch Metadata 等浏览器安全参数进入公开发布面。

当前没有已正式发布的 Java SDK 制品，因此不建立虚构的二进制兼容基线。首个正式版本发布后，后续版本才以真实发布制品启用二进制兼容比较。

## 身份与上下文

Starter 将 Spring Security Resource Server 与 IAM JWKS 端点集成：按 JWT `kid` 缓存公钥并支持签名密钥轮换。用户 Access Token 只包含：

```text
identityId
membershipId
tenantId
jti
```

SDK 对每个用户请求验证签名、有效期和 Redis `jti` 黑名单；黑名单不可用时 fail-closed。SDK 提供只读上下文：

```java
TenantContext.getTenantId();
IdentityContext.getIdentityId();
MembershipContext.getMembershipId();
```

上下文只能由已验证 Token 建立。用户请求不得通过请求头、查询参数、请求体或任何语义等价别名传入或覆盖 Tenant；这类输入必须在建立上下文前以 `400` 拒绝，业务代码也不得覆写上下文。Client Credentials 令牌不建立上述用户上下文；服务授权只基于 `client_id` 与显式 `scope`，不得伪造用户、Membership、Tenant 或用户 RBAC 上下文。

上述只读保证遵循[产品开发信任边界](01-product-scope.md#使用方与开发信任边界)：平台与业务服务由 SaaS 厂商同一批可信开发者维护，Tenant 不自行开发或部署接入业务。只读上下文约束正式 SDK 接口与请求处理流程，不承诺隔离同一应用进程内刻意绕过安全机制的开发者代码。

## 后续授权、权益与配额

以下能力是后续阶段的设计方向，不属于首版发布面；相应 SDK 当前只是不会发布的 Reactor 占位模块。

| 能力 | SDK 行为 | 一致性规则 |
|---|---|---|
| Permission | `@RequirePermission` 或编程式检查 | JWT 不携带权限；SDK 先查本地短缓存，未命中时经 Gateway 查询 Tenant Access；Kafka 事件失效缓存 |
| Feature | `@RequireFeature` 或编程式检查 | 与 Permission 相同；两项校验可同时要求 |
| Quota | `check`、`consume`、`release`、`usage` | 始终同步调用 Entitlement；`consume/release` 带稳定 `operationId`，不以本地缓存作为额度真相 |
| Audit | `@Audit` 或 `audit.log` | 将最小必要审计事件异步投递到 Audit 服务；不得记录凭据或原始敏感个人信息 |

Permission 与 Feature 默认使用有容量上限的进程内短缓存。业务项目可以通过 SDK 缓存接口替换为自己的 Redis 实现，但必须使用业务项目自己的命名空间、凭据和 Registry，不得访问平台 Redis；缓存未命中、过期、失效或不可用时经 Gateway 回源权威接口。平台接口也不可用时 fail-closed，不使用已过期的允许结果。Kafka 失效事件用于快速收敛，短 TTL 负责事件丢失时的最终收敛。

业务应用可声明：

```java
@RequireFeature("lis.report")
@RequirePermission("lis:report:list")
public List<Report> list() {
    return reportService.list();
}
```

## 服务调用与韧性

- 首版 `sdk-core` 只提供从正式 OpenAPI 显式安全子集生成的低层 REST Client；消费者显式配置 Gateway 地址和 operation 所需凭证，默认地址不可用于真实部署。
- 首版保留生成 Client 的 HTTP 状态、响应体与传输失败契约，不增加完整 Problem Details 领域异常映射。
- 首版不自动重试，也不提供熔断或领域 façade；调用方必须按 operation 的幂等契约和失败结果显式处理。后续领域 SDK 若增加韧性策略，必须独立评审其幂等和安全边界。
- 业务系统通过 API / SDK 集成，不获得平台数据库访问权限。

## Starter 配置边界

Starter 从共享 Route Catalog 选择本服务的 HTTP operation，默认装配 Resource Server Bearer 过滤器、Nacos 发现的 IAM JWKS 与 Redis 撤销适配。常规消费者无需提供签名与撤销适配器；既有完整自定义适配方式保留兼容，部分自定义适配仍启动失败，不存在允许型回退。

第 3 阶段的 Starter 身份检查任务在此基础上补齐 Spring Security Resource Server、IAM JWKS、公钥缓存与轮换、Redis fail-closed 撤销检查和只读上下文，并完成对应专项验收。该任务范围不包含 Project/Task 页面、业务 API 或数据库 RLS；这些能力及真实页面跨租户隔离验收继续由 [MVP 开发计划第 3 阶段](16-mvp-development-plan.md#3-sdk-与-example-租户隔离闭环)的后续条目交付。身份检查任务完成不代表第 3 阶段完成。

接入业务服务统一使用平台提供的 Starter 身份认证机制，厂商开发者在该身份基础上实现领域业务与后续授权。当前任务不以兼容任意既有第三方认证配置为目标；防止重复安全配置或认证链遗漏属于实现与验证责任，不另设产品接入模式。

Tenant Context、授权和审计的公共 API 是稳定集成面；平台内部服务或数据库实体不是 SDK 兼容性承诺。

### 第 3 阶段 JWKS 缓存与刷新决策

实现遵循以下已确认行为，实际验证结果单独记录：

- 公钥缓存有效期遵循 IAM JWKS 的 5 分钟窗口。IAM 暂时不可达时，未过期且包含目标 `kid` 的缓存仍可用于验签，但每次请求仍须通过撤销状态检查；缓存到期后无法成功更新时，拒绝受保护请求，不延长旧缓存的有效期。
- 未知 `kid` 触发的刷新按业务服务实例合并，同一实例同时最多执行一次 JWKS 获取，并限制刷新频率。无法确认的凭证不得放行；已有未过期公钥可验证的请求不等待未知 `kid` 的刷新，但仍须通过全部认证及撤销检查。
- 刷新初始参数为同一实例两次 JWKS 获取的发起时间至少间隔 10 秒、单次请求等待公钥获取最多 2 秒；并发请求共用在途获取，同一次请求内不反复重试。参数允许受控配置调整，但不得取消限频或采用无界等待；这些初始值尚未经性能实测。
- 必需配置缺失时启动失败。启动时 IAM 或 Redis 暂时不可用，进程保持运行但 Readiness 不就绪，不接收正常业务流量；取得可用公钥且撤销检查恢复后自动进入就绪状态。依赖恢复前不得放行受保护请求。

### 身份检查专项验收范围

验收沿用既有 Route Catalog、User/Service Token 互斥、错误响应与撤销索引契约，不以业务页面或 Project/Task 数据隔离作为本任务完成条件：

- 有效 User/Service Token 分别建立正确且互斥的上下文；错误算法、签名、Issuer、Audience、时间、Claim 或 Token 类型被拒绝，Service Scope 缺失按既有契约拒绝。
- 缓存命中、未知 `kid` 并发与限频、公钥获取超时、缓存过期以及正常轮换符合上述决策；错误或失败的刷新不得延长旧缓存有效期。
- 用户请求覆盖 `jti`、`kid`、Membership/Tenant Revocation Fence 和 Index Ready；服务请求覆盖 `kid`、`client_id` 和 Index Ready。撤销命中拒绝，Redis 故障或索引未就绪失败关闭，即使公钥仍在缓存中也不得绕过。
- 外部伪造上下文被拒绝，公开上下文不可写，请求结束后清理且不串入下一次请求；异步线程不自动继承用户上下文。经过 Gateway 与直连接收端均不能绕过应有检查。
- 缺少必需配置启动失败；依赖暂不可用时进程存活但不就绪，依赖恢复后自动就绪，期间受保护请求不得放行。
- 以真实 IAM、Redis、Nacos、Gateway 与 Starter 接收端完成机制专项验收，并辅以可控时钟、并发和故障测试覆盖边界。结果区分通过、失败、跳过和未执行，不把测试夹具或本项完成记为第 3 阶段产品闭环完成。

## 最小 HTTP 认证 Starter

首个可投产 Starter 切片只负责 HTTP 接收端认证：消费与 Gateway 相同的版本化 Route Catalog 制品，按操作建立不可变的 User Principal 或 Service Principal，并在业务 Controller 之前再次校验令牌类型、签名、标准 Claim、撤销状态与 Service Scope。两类 Principal 互斥；Service Principal 只暴露 `client_id` 与已授予 Scope，不建立 Identity、Membership、Tenant 或用户 RBAC 上下文。Starter 不自动跨线程传播安全上下文，异步边界必须显式提取最小必要值并重新建立受控上下文。

Gateway 与 Starter 的职责、错误语义、保留请求头和真实接收端验收夹具见 [Gateway Service Scope 路由设计](23-gateway-service-scope-routing.md)。
