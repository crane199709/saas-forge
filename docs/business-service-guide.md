# Business Service 接入指南

需要 JDK 17、Maven 3.9+、可用 Docker（Testcontainers）以及平台发布的 Maven 仓库。在 Maven settings.xml 配置该仓库和凭证，所有 Forge 制品须使用同一发布版本。以下以 SDK 0.1.0 为例。

## 生成与验证

在新仓库目录执行：

```sh
mvn org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate -B \
  -DarchetypeGroupId=io.saasforge -DarchetypeArtifactId=forge-service-archetype \
  -DarchetypeVersion=0.1.0 -DgroupId=com.example -DartifactId=orders-service \
  -Dversion=1.0.0-SNAPSHOT -Dpackage=com.example.orders \
  -DserviceName=orders-service -DpathPrefix=orders
cd orders-service
mvn verify
```

`serviceName` 是注册名和 Manifest 上报身份；`pathPrefix` 不带 `/`，是网关前缀及示例 Sub-App 编码。根包用 `package`。业务工程版本独立于 SDK 版本；BOM 的 forge.version 锁定 SDK 发布版本，升级时调整父 POM 版本。

工程继承发布的 `forge-dependencies`，包含十个 SDK starter、Boot 插件、共享架构规则，以及真实 PostgreSQL/Redis 容器测试。测试使用自身 classpath 的数据库初始化脚本与测试签名密钥，不要求克隆平台仓库，也不连接共享数据库。`verify` 包含 HTTP 租户隔离、功能权限、Data Policy 及 ArchUnit；MQ/Nacos/Manifest 远程连接在测试配置关闭，因此该验证不替代平台联调。

示例复用 demo-service 的订单 CRUD、分类 Provisioning、Outbox 通知和统一信封；不默认引入 storage。订单接口在服务内为 `/orders`，网关 StripPrefix 后外部为 `/orders/orders`。上线前把示例领域、全局权限码 `order:*`、Data Resource `order`、菜单、角色模板替换为本服务领域，避免与已有服务声明冲突。

维护模板时，在平台仓库执行 `cd backend && ./mvnw install -Parchetype-it`；该 profile 启用 archetype 自带的生成集成测试。普通 reactor `verify` 不运行这一独立 Maven 消费接缝，避免冷缓存下引用尚未安装的同次 SDK；已发布/安装 SDK 后也可单独 `./mvnw -pl forge-service-archetype clean verify -Parchetype-it`。CI 使用包含模板测试的 install 命令。

## 从配置到租户菜单

1. 与平台运维按[平台侧接入清单](service-onboarding-checklist.md)第 1 步准备目标数据库、schema、运行与迁移账号。业务开发者只维护 Flyway 迁移，不能用运行账号建表。租户表保留标准列、ENABLE + FORCE RLS、部分唯一索引与 `NOT deleted` 条件。
2. 按清单第 2 步登记服务专属 Manifest 令牌，第 3 步创建 `forge-tenant`、`forge-app`、`forge-audit` 及 `<serviceName>-order` Topic；platform 的 `forge.platform.provisioning.services` 加入服务注册名。
3. 提供下表配置后执行 `mvn spring-boot:run`。确认 Flyway 成功、Nacos 注册成功、IAM Manifest 上报成功。服务仅导入 `forge-common.yaml`；如使用独立服务配置，必须显式添加其 dataId，不能只在 Nacos 创建文件。
4. 按清单第 4—5 步追加网关路由和 `forge.gateway.services` 的 `prefix: /orders`、`realm: tenant`，重启网关。匿名访问业务接口得到 401，正确 Tenant Realm Token 按权限得到 200/403，OpenAPI 能访问且 servers 地址正确。
5. 按清单第 6 步发布对应 Sub-App、登记 App Registry（示例 app 编码为 pathPrefix）、向套餐开放应用和权限，再向成员角色授予权限及 Data Policy。重新登录租户 Shell，确认看到菜单、加载前端资源并成功调用服务。只有后端 Manifest 并不会自动生成可用的前端页面。

## 必须配置项

生成的 application.yml 不为环境地址和密码设默认值；通过环境变量或等价外部配置提供：

| 环境变量 | 含义 / 提供方 |
|---|---|
| FORGE_SERVICE_PORT | 服务监听端口 / 部署运维 |
| FORGE_NACOS_ADDR | Nacos 地址 / 清单第 4 步 |
| FORGE_DB_URL | 完整 JDBC PostgreSQL URL / 清单第 1 步 |
| FORGE_DB_USER、FORGE_DB_PASSWORD | 运行账号和密码 / 清单第 1 步 |
| FORGE_DB_MIGRATOR_USER、FORGE_DB_MIGRATOR_PASSWORD | schema 所有者账号和密码 / 清单第 1 步 |
| FORGE_DB_SCHEMA | 数据库准备脚本使用的短名 / 清单第 1 步 |
| FORGE_REDIS_HOST、FORGE_REDIS_PORT | 平台共享 Redis / 平台运维 |
| FORGE_SECURITY_ISSUER | Tenant Realm 的公开 issuer，与 Token 一致 / 平台运维 |
| FORGE_SECURITY_JWK_SET_URI | IAM JWKS 的服务直连 URL / 平台运维 |
| FORGE_ROCKETMQ_ENDPOINTS | RocketMQ Proxy gRPC 地址 / 清单第 3 步 |
| FORGE_IAM_URI、FORGE_MANIFEST_TOKEN | IAM 内部地址及专属令牌 / 清单第 2 步 |

环境如启用 Redis/Nacos/MQ 的认证，另按平台配置提供其凭证；本模板不内置任何共享环境凭证。`forge.manifest.version` 初始为 1，是清单版本，不是 Maven 版本。

## Manifest、Provisioning 与内部接口

权限码、名称、菜单、角色模板、Data Resource 或资源属性变化时递增 `forge.manifest.version`，与新清单一起发布；相同版本不能用于覆盖不同内容。JSON 在 `META-INF/forge/manifest.json`，权限注解与清单必须一致。

`OrderCategoryService` 实现 `TenantInitializer`，SDK 订阅 TenantCreated、恢复 CurrentTenant 并在初始化事务中调用它；业务层不自行处理租户 ID。初始化只补齐缺少的分类，部分唯一索引兜底并发，SDK 的消费去重与事务确保重投不会产生重复结果。失败时让异常传播以触发重试，不吞掉错误；已有初始化数据不得被重试覆盖。清理实现 `TenantDataCleaner`，保持逻辑删除。用平台测试租户确认初始化状态成功，纯 HTTP 测试不能证明真实 MQ 投递。

内部 HTTP 接口必须放在 `/internal/` 下，网关拒绝该前缀；网络访问控制与服务自身认证仍由部署和实现保障。不要把内部接口改成其他路径绕过网关限制。

## 按需启用 storage 与镜像

需要附件时在 POM 添加同版本 `io.saasforge:forge-sdk-storage`，由平台提供 `forge.storage.endpoint`、`bucket`、`access-key`、`secret-key`；endpoint 必须供浏览器访问。参考 demo-service 的 attachment 功能及 V8 迁移，不应只添加依赖却遗漏配置和存储表。

镜像统一采用 Spring Boot build-image；工单 07 完成后补齐与平台相同的构建配置和命令。当前工程可先 `mvn package`，使用 `java -jar target/orders-service-1.0.0-SNAPSHOT.jar` 启动。

## 常见失败

| 现象 | 检查 |
|---|---|
| 无法解析 Forge 依赖 | 仓库 settings、凭证及 SDK 统一版本；不要用业务工程版本替换 SDK 版本 |
| 未解析的占位符、配置绑定失败 | 上表必须项是否注入；避免在测试之外使用测试固定值 |
| Flyway schema 不存在或 permission denied | 准备脚本是否对正确数据库执行、迁移账号是否拥有 schema、运行账号是否误作迁移账号 |
| ltree 类型不存在 | DBA 是否在 extensions 安装扩展、账号 search_path 是否按准备脚本设置 |
| JWT 401 | issuer 是否与实际 Tenant Realm Token 完全一致，JWKS 地址是否可达 |
| 已认证却 403 / 列表为空 | 套餐、成员角色权限及 Data Policy；无 Data Policy 默认拒绝 |
| Manifest 被拒 | 注册名与 IAM token 配置键是否一致、令牌和清单 version 是否正确 |
| MQ 启动失败 / 初始化 pending | 使用 Proxy gRPC 而非 nameserver 地址、Topic 是否创建、platform 是否登记服务 |
| 看不到菜单或加载失败 | Manifest、App Registry、套餐、角色、远程入口地址与 CORS；参照清单第 6 步 |
