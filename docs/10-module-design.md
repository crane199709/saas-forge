# saas-forge 模块设计

> **2026-09-29 前端迁移状态**：统一 Console 已在独立 saas-forge-web 实现，v2 已实现但启用须受控切换，见 [ADR 0052](adr/0052-unified-console-authentication-uses-versioned-session-protocol.md)。下文双 Console、自建包、`consoles/`、旧命令/截图数量及其完成时态保留原设计或历史事实，不代表当前入口与验收。当前检查见[测试归属](console-testing-baseline.md)，未迁及待聚合项见[#207 清单](acceptance/issue-207-cleanup.md)；原历史勾选不自动证明父 #201 完成。

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

## 顶层结构

以下为当前实际目录结构。Maven 模块以各自的 `pom.xml` 为准；无 `pom.xml` 的目录是文档或契约资料目录，不是 Maven 模块。

```text
saas-forge
├── saas-forge-services
│   ├── iam-service
│   ├── tenant-access-service
│   ├── entitlement-service
│   ├── audit-service
│   └── saas-forge-service-discovery        # Nacos gRPC 服务发现支持库，不是可部署应用
├── gateway
├── saas-forge-contracts
│   ├── saas-forge-http-route-catalog        # Maven 模块
│   ├── saas-forge-openapi-contracts         # Maven 模块
│   ├── saas-forge-protobuf-contracts        # Maven 模块
│   ├── saas-forge-event-contracts           # Maven 模块
│   ├── compatibility-baselines              # 契约基线资料，非模块
│   ├── redis / logging / security / services # 契约资料，非模块
├── saas-forge-sdk
│   ├── saas-forge-java
│   │   ├── saas-forge-bom                   # BOM
│   │   └── saas-forge-sdk-{core,auth,tenant,permission,feature,quota,audit}
│   └── saas-forge-starters
│       └── saas-forge-spring-boot-starter
├── saas-forge-quality-gates
├── test-support                             # 仅由 Maven profile 激活
│   ├── platform-mechanism-receiver
│   └── saas-forge-external-consumer-fixture
├── consoles
│   ├── platform-console
│   ├── tenant-console-shell
│   ├── shared                               # admin / app-runtime / api-client / i18n
│   ├── business-remotes                     # 目前只有验收夹具
│   ├── static-remote-acceptance
│   └── test / browser-test / integration-test
├── examples                                 # 目前只有 README，无源码
├── deploy
│   ├── compose                              # 共享基础设施
│   ├── acceptance                           # 组合验收
│   ├── docker / postgresql / nacos
│   ├── helm                                 # 目前只有接入契约文档，无 Chart
│   └── systemd
├── docs
└── scripts
```

API Gateway 是边界组件，不计入领域服务数量。它不持有领域数据，也不承载领域规则。

所有 Maven 模块（含聚合模块与验收夹具）的末级目录名、`artifactId` 和 POM `<name>` 保持一致。目录调整保留原有 Maven 坐标；前端 workspace 包命名和事件 Schema URL 等协议标识独立于文件系统路径。

## 服务边界与数据所有权

| 服务 | 负责的领域 | 独占数据 | 同步协作 |
|---|---|---|---|
| `iam-service` | Identity、密码凭据、会话、JWT、Refresh Token、Client Credentials、JWKS | Identity、Credential、Refresh Token Family / Token、OAuth Client 与 Secret 元数据、会话与令牌撤销记录、签名密钥元数据 | 登录与 Tenant 切换时调用 Tenant Access 验证 Membership；为 Tenant Access 提供 Identity/凭据建立与会话撤销 |
| `tenant-access-service` | Tenant、Membership、Tenant 管理员初始化、Tenant 生命周期与品牌档案；Organization、通用 RBAC 目录与 Invitation 激活**未实现** | Tenant、Membership、Tenant Role 与角色绑定、品牌档案、创建/初始化/密码投递/生命周期工作流及补偿记录 | 为 IAM、SDK 提供成员和授权查询；编排管理员初始化、成员禁用与 Tenant 冻结 |
| `entitlement-service` | Plan、Subscription、Quota Definition 与 Quota 计量；Feature 运行时闭环与订阅版本化**未实现** | Plan 与 `plan_quotas`、Subscription、Quota Definition / Usage / Operation 及幂等与恢复记录 | 为 SDK 提供配额判定；额度上限实时从 Plan 读取，当前没有不可变权益快照 |
| `audit-service` | 统一审计与成功事实消费；审计查询与导出**未实现** | 只追加 Audit Record、消费去重与隔离处置表 | 消费其他服务与业务系统的已提交领域事实事件 |

服务之间禁止共享领域代码、实体、数据库模型、数据库表和迁移。跨服务共享物仅为版本化 OpenAPI / Protobuf / 事件契约、Redis 安全基础设施契约、日志 Schema、通用安全与可观测性库以及构建 BOM。契约类型在服务边界映射为各服务自己的内部模型。

## 通信规则

```text
External client → Gateway → REST service endpoint
IAM ↔ Tenant Access → gRPC
SDK → Gateway → REST runtime endpoint
All services → Kafka Outbox → Audit / cache invalidation consumers
```

- 需要即时结论的认证、成员校验、Permission / Feature 查询、Quota 判定与会话撤销走同步路径；Kafka 不承担这些路径的成功判定。
- Tenant、Membership、Identity、Subscription 与权益状态变更通过 Kafka 通知其他服务收敛缓存和审计。流程根服务、同步调用顺序与恢复责任以[跨服务工作流契约](18-tenant-access-cross-service-workflows.md)为准。
- 所有领域事件采用 Transactional Outbox：业务事务同时写入领域数据和 Outbox；发布器可靠投递 Kafka；消费者按事件 ID 幂等。事件只表达来源服务已提交的事实，不作为跨服务命令。

## 前端模块

`platform-console` 与 `tenant-console-shell` 是两个独立部署的 Vue 3 + Element Plus 应用，共享 `@saas-forge/admin`（Soybean 布局、认证界面、品牌与 Locale）、`@saas-forge/app-runtime`（无 UI 认证状态机）、`@saas-forge/api-client`（生成式 REST Client）与 `@saas-forge/i18n`。它们在各自受控 Origin 独立运行；允许的浏览器来源只有 `platform.<root>` 与 `console.<root>`。`consoles/business-remotes/admin-consumer-fixture` 只是验证共享 UI 消费边界的夹具。

以下属于**设计目标、尚未实现**，不得按已交付对待：

- 业务模块以 Module Federation Remote 独立构建、独立部署；仓库当前无任何 Module Federation 配置或产品 Remote。
- 仅由经审核的版本化 Manifest 加载 Remote：Manifest 由业务模块 CI 以 Client Credentials 注册，包含远程入口、页面、菜单、Permission 与 Feature，平台管理员只能审核、启停和查看。
- Remote 只能使用宿主暴露的认证 API 与共享 HTTP Client，不能读取或存储 Token。该约束在 Remote 真正实现后仍然适用。

## 模块依赖方向

- Gateway 可依赖契约与通用安全/可观测性库，不依赖领域实现。
- 每个领域服务可依赖自身领域模块、契约与通用库；不得反向依赖其他服务的领域实现。
- SDK 只依赖公共 REST / JWKS 契约，不依赖内部 gRPC 或领域数据库模型。
- Console 只依赖公开 API Client 和注册的前端契约，不直接访问服务数据库。

## 服务登记与 HTTP 路由契约

`saas-forge-contracts` 维护受控 Service Registry 与独立 Scope Registry；它们只登记可部署服务、所有权和合法 Scope，不承载运行时实例地址。公共 OpenAPI 仍是操作、路径和安全声明的唯一事实来源。构建期将 OpenAPI、两个 Registry 与服务所有权校验后生成版本化 Route Catalog JSON，并发布为 Gateway 与 Spring Boot Starter 共同消费的 `saas-forge-http-route-catalog` 制品。Gateway 不因 Nacos 出现新实例而自动开放公网路由。完整边界见 [ADR 0034](adr/0034-controlled-service-registry-and-route-catalog.md)与 [Gateway Service Scope 路由设计](23-gateway-service-scope-routing.md)。

Audit 只消费来源服务已经提交并注册的事实事件；首个切片仅覆盖 Session Started、Tenant Created、Tenant Context Switched，采用两个独立消费者身份写入只追加 Audit Record。存储、隔离和重放边界见 [Audit 成功事实消费设计](24-audit-success-fact-consumption.md)。
