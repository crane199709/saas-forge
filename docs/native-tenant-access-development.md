# Tenant Access 与 IAM 原生联调

本说明对应 Issue #164，延续 [Gateway/IAM 原生启动](native-platform-auth-development.md) 和 ADR 0043、0044。使用已有合法 Tenant、Identity 和启用的 Membership；Tenant 初始化所需 Entitlement 链路见 [Issue #165 联调说明](native-entitlement-development.md)。

## 个人配置与独立准备

按[开发配置说明](development-configuration.md)设置 IDE 连接参数和凭据目录。直接启动，默认从当前开发 Nacos 读取业务配置；不保留实际本地业务 YAML 或模板。只有显式激活 `local-file` 才按文档自行创建个人业务配置。

Tenant Access 必需凭据由环境变量或权限受限的外部 Spring configtree 注入：

| 配置 | 用途 |
| --- | --- |
| `NACOS_TENANT_ACCESS_USERNAME` / `NACOS_TENANT_ACCESS_PASSWORD` | 应用专属 Nacos 身份 |
| `TENANT_ACCESS_APP_PASSWORD` | `tenant_access_app` 的数据库密码 |
| `REDIS_PASSWORD` | 共享撤销索引的 Redis 凭据 |
| `IAM_JWT_ISSUER` | 与 IAM/Gateway 一致的 issuer |
| `SAASFORGE_SERVICE_CLIENT_ID_FILE` / `SAASFORGE_SERVICE_CLIENT_SECRET_FILE` | Tenant Access 自己的保留 Service Client 文件 |
| `IAM_SERVICE_CLIENT_ID_FILE` | IAM 保留 Service Client 的 ID 文件，用于 Membership 调用方校验 |

文件路径为绝对路径；Secret 文件建议 600、父目录 700。不要将 Client Secret、数据库密码或 Token 粘贴到个人 YAML。数据库迁移及保留 Service Client 的初始化独立完成，常驻应用不提供 migrator 或管理员凭据，不重置已有数据、不替换已有 Client。

连接示例使用数据库 `tenant_access_db`、应用账号 `tenant_access_app`，PostgreSQL/Redis/Kafka/Nacos 地址分别为 `127.0.0.1:5432/6379/29092/8848`。通过 IDE 连接设置改为实际环境；Kafka 若启用认证，还需注入对应凭据与协议。应用账号、namespace、issuer、环境和服务身份必须匹配实际初始化结果。

### 发现权限与 gRPC 注册

既有环境由 Nacos 管理员仅补充以下 naming 读取权限；不要为了补权限重新发布全部配置或重新初始化业务数据：

- `iam-service-dev`：`dev:DEFAULT_GROUP:naming/tenant-access-service`，action `r`。
- `tenant-access-service-dev`：`dev:DEFAULT_GROUP:naming/iam-service`，action `r`。

全新 dev 环境的 `deploy/compose/nacos-init.sh` 已包含这两项。非 dev namespace/角色使用实际值，不照抄 dev。应用间不授予对方配置读取、发布或注册权限。`scripts/verify-nacos-acl.sh` 验证双向发现读取并保留配置隔离检查；它需要相应工作负载凭据，不应给 IDE 应用提供管理员凭据。实例列表检查使用 [Nacos Client API](https://nacos.io/docs/latest/manual/user/open-api/)。

**IAM 与 Tenant Access 的 `application.yaml` 已包含以下 metadata**，无需在 IDE 重复填写。元数据来自本实例的 gRPC 监听端口，不是调用方的下游地址配置：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        metadata:
          grpc.port: ${spring.grpc.server.port}
```

HTTP 目标取实例 IP 与 HTTP 注册端口；gRPC 目标取同一实例 IP 与 `grpc.port`。每次调用直接查询 Nacos 健康实例，不依赖订阅缓存；发现异常、无健康实例、缺少或非法 gRPC 端口时明确拒绝，不回退默认容器地址。已建立的 gRPC 连接也必须先通过这次发现查询。gRPC 地址由 Nacos 提供，传输安全沿用各命名通道的 TLS/SSL Bundle 配置；调用最多 3 秒，浏览器仍经受信 HTTPS。

## IDE Run / Debug

保留 Gateway 与 IAM 的运行配置。新增 Java/Spring Boot 配置：主类 `io.saas.forge.tenantaccess.TenantAccessServiceApplication`，classpath `tenant-access-service`，Active profiles 留空。只保留 IDE Build，不添加 package、后台 JAR 或 replace/restore 前置任务。

Tenant Access 默认 HTTP 8082、gRPC 9092、注册 IP `127.0.0.1`。可配置 `TENANT_ACCESS_HTTP_PORT`、`TENANT_ACCESS_GRPC_PORT`、`TENANT_ACCESS_REGISTER_IP`、`TENANT_ACCESS_BIND_ADDRESS`。IAM 的对应变量见前一切片说明。注册地址必须从调用方可达；同一个服务本机与容器冲突由开发者处理，本入口不自动停容器或接管进程。

## 真实链路验收

按[原生开发总入口](native-local-development.md)准备 HTTPS Edge，并按 [saas-forge-web](https://github.com/crane199709/saas-forge-web) 说明启动真实 Tenant Console。浏览器仅访问 `https://console.saas.forge.test` 和既有 HTTPS API Gateway，不手工注入 Cookie、Origin、Fetch Metadata 或 Bearer Token。

1. 在三个应用的 IDE Console 确认启动。Tenant Access 可在 `MembershipValidationGrpcService.validateMembership` 放断点，IAM 可在 `AuthenticationController.refreshAccessToken` 放断点，以真实请求确认并恢复执行；修改后由 IDE 编译、重启。
2. 用已有合法 Membership 的 Identity 登录 Tenant Console，必要时选择 Tenant；刷新页面，确认 Tenant Session Slot 恢复及当前 Tenant 正确。IAM 的 Membership 校验携带内部签发的保留服务 Token，Tenant Access 通过发现的 IAM JWKS 和共享撤销索引复验。
3. 在 IAM `ReservedIamServiceAccessTokenProvider.membershipReadToken`、Tenant Access `MembershipValidationServerInterceptor` 和 IAM JWKS 入口记录证据：服务 Token 在 IAM 内部签发，经 IAM → Tenant Access gRPC 校验，再由 Tenant Access → IAM HTTP 获取 JWKS。不要把 Tenant Access 健康注册当作反向调用证据。Tenant Access 自身的 `/oauth2/token` 与 IAM gRPC 适配也已接入发现，但 Tenant 登录/刷新并不触发 Platform Role、Identity 创建或 Session Revocation；这些额外路径的自动化验证须单独记录，不能宣称已由登录覆盖。
4. 改 Tenant Access 的 HTTP/gRPC 端口后重启，调用方配置保持不动，重试登录/刷新。再改 IAM 的 HTTP/gRPC 端口并重启，以已有页面重复对应操作；注册表中的 `grpc.port` 必须随被调用方更新。
5. 分别停止被调用实例并确认没有健康实例后重复对应操作，必须明确失败；恢复实例后重试。发现失败的自动化证据与真实 Nacos 故障演练分别记录，不以注册成功或模拟测试替代真实联调。

## 自动化验证

```bash
./mvnw -pl saas-forge-services/saas-forge-service-discovery,saas-forge-services/tenant-access-service,saas-forge-services/iam-service -am \
  -Dtest=LocalConfigurationTest,NacosServiceEndpointsTest,DiscoveredGrpcChannelTest,SecurityAdapterTest,ReservedIamServiceAccessTokenProviderTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

相关边界的完整模块检查使用 `./mvnw -pl gateway,saas-forge-services/iam-service,saas-forge-services/tenant-access-service -am verify`。测试会使用临时数据库/Redis/Kafka 等 Testcontainers；它不托管日常应用。仓库完整 CI、Fresh Compose 和 Chrome 产品验收继续独立。

验收记录见 [Issue #164](acceptance/issue-164-native-tenant-access.md)，明确区分自动化、真实 IDE/浏览器和未执行项。
