# Gateway 与 IAM 原生本地开发

适用于 Issue #163 的 Platform 登录与刷新切片，遵循 ADR 0043、0044。应用由 IDE 直接 Run/Debug 主类；环境初始化、迁移、服务身份及 HTTPS Edge 独立准备。Tenant 会话所需的双向 gRPC 发现见 [Issue #164 联调说明](native-tenant-access-development.md)，本文件的 Platform 切片不代替该验收。

## 个人配置

按[开发配置说明](development-configuration.md)设置 IDE 连接参数；默认读取各自的 Nacos dev 配置，不保留实际本地业务 YAML 或模板。只有显式激活 `local-file` 才使用自己按文档创建的个人文件。

凭据通过各应用独立的环境变量或受限 configtree 提供；`application.yaml` 保存连接参数与占位符，本机默认值及凭据目录导入已在 YAML 中，IDE 工作目录设为仓库根目录即可。私密文件名是对应属性名，例如 `NACOS_IAM_PASSWORD`，目录 700、文件 600。不导入整份 Compose 管理员环境。

| 应用 | 必需值或引用 |
| --- | --- |
| Gateway | `NACOS_GATEWAY_USERNAME`、`NACOS_GATEWAY_PASSWORD`、`REDIS_PASSWORD`、`IAM_JWT_ISSUER` |
| IAM | `NACOS_IAM_USERNAME`、`NACOS_IAM_PASSWORD`、`REDIS_PASSWORD`、`IAM_APP_PASSWORD`、`IAM_JWT_ISSUER`、`IAM_JWT_PEM_KEY_VERSION_REF`、`IAM_JWT_PEM_PRIVATE_KEY_LOCATION`、`SAASFORGE_SERVICE_CLIENT_ID_FILE`、`SAASFORGE_SERVICE_CLIENT_SECRET_FILE` |

私钥位置使用 `file:/absolute/path/to/key.pem`；Client ID 与 Secret 使用受限文件的绝对路径。签名版本引用必须与数据库已初始化的 ACTIVE Signing Key 一致，不能生成新私钥后直接替换既有数据库的签名元数据。两个应用的 issuer、环境和 Browser Root Domain 必须一致。

文档中的连接示例使用本机开发依赖：PostgreSQL 5432、Redis 6379、Kafka 29092、Nacos 8848。这些是基础设施地址示例，不是必需容器拓扑；可通过 IDE 连接设置改为自己的环境。启用认证的 Kafka 需要另外注入其协议、机制及受限凭据属性。Nacos namespace、应用身份和网络可达性须与实际环境匹配，Nacos 客户端还需要能连接服务端 gRPC 端口。

## 独立准备

- 先由既有受控初始化任务完成 IAM Flyway 迁移、Signing Key、保留 Service Client 和 Platform Admin 的准备。复用现有数据及有效凭据时不重跑重置或替换流程。
- 常驻 IAM 默认使用 `iam_app`，只注入应用数据库密码；`spring.flyway.enabled=false` 保持不变。不提供 migrator、数据库管理员、Nacos 发布管理员或 Platform Admin 初始化凭据。
- 首次环境准备可参考 [Compose 初始化说明](../deploy/compose/README.md)，但不把完整 Compose 或后台 JAR 脚本设为 IDE 启动前任务。数据库、Redis、Kafka、Nacos 可独立存在于本机或可达的开发环境。
- 先检查同一服务的容器和本机实例。由开发者停止冲突实例并等其从注册表下线；本入口不会接管、替换或恢复其他进程。

## IDE Run / Debug

Maven 项目使用根 POM 支持的 JDK 17。首次导入并同步 Maven，使生成的契约源码和模块依赖进入 IDE 类路径；日常修改由 IDE 编译，不执行 `package`、JAR 启动或脚本生成参数。

| 配置 | Main class | 模块 classpath | Active profiles |
| --- | --- | --- | --- |
| Gateway | `io.saas.forge.gateway.GatewayApplication` | `gateway` | 留空 |
| IAM | `io.saas.forge.iam.IamServiceApplication` | `iam-service` | 留空 |

普通 Java Application 无需填写 profile 参数；移除旧的 `--spring.profiles.active=local`。IDE 启动前动作保留普通 Build，不添加 Maven package、Compose 或托管脚本。默认通过 Nacos 发现服务并读取配置；`local-file` 仅用于显式选择本地业务文件。若曾按旧说明设置 `spring.config.additional-location` 指向模块 `config/`，请移除该参数。

先 Debug IAM，再 Debug Gateway。两个主类可同时运行，分别停止和重启；日志留在对应 IDE Console。Gateway 默认 HTTP 8080，IAM 默认 HTTP 8081、gRPC 9091。用 `GATEWAY_HTTP_PORT`、`IAM_HTTP_PORT`、`IAM_GRPC_PORT` 调整监听；`GATEWAY_REGISTER_IP`、`IAM_REGISTER_IP` 决定注册表发布的地址，必须从调用方可达。注册 HTTP 端口随对应 HTTP 监听端口变化。

连接示例中 IAM 绑定 `127.0.0.1`，Gateway 为配合 Docker HTTPS Edge 使用 `0.0.0.0`。可用 `GATEWAY_BIND_ADDRESS` 调整为 Edge 可达的本机接口；监听地址不成为浏览器入口。其他服务的容器需要访问本机 IAM 时，也必须先确认其网络可达性，不能把本机 `127.0.0.1` 当成容器内的目标地址。

## 真实浏览器认证

按[原生开发总入口](native-local-development.md)准备证书、域名、现有 Console 和 Edge。浏览器仅访问 `https://platform.saas.forge.test`。Edge 的现有 `deploy/compose/.secrets/local-service-replacement/api-target.json` 可设为：

```json
{"hostname":"host.docker.internal","port":8080}
```

这只配置 HTTPS 入口到 Gateway 的目标。Gateway → IAM 的路由与 JWKS 使用已有 Nacos 发现；IAM 使用内部 Client Credentials 应用服务获取自身服务令牌，保留 Client 凭据、Scope 与撤销检查，不再通过 HTTP 查找和调用自身，也不需要自身发现读取权限。

使用已有 Platform Admin 的常规密码在真实 Console 登录，刷新页面触发 Platform Session Slot 恢复。确认请求走 HTTPS API Origin、刷新携带 `sessionSlot: PLATFORM`，响应 Cookie 保持 host-only、Secure、HttpOnly、SameSite=Strict。不要在 Console 调用参数中手工注入 Origin、Cookie、Fetch Metadata 或 Bearer Token。

在 Gateway 路由处理入口及 IAM `AuthenticationController.login` / `refreshAccessToken` 放断点，以真实请求命中并恢复执行。修改后由 IDE 重新编译、重启，确认仍能登录/刷新。再改变 IAM HTTP 端口并重启，等待 Nacos 更新，保持 Gateway 下游配置不动，重复登录/刷新。

停止 IAM 并确认注册表无健康目标时，Gateway 应明确失败；不得切换到固定地址。发现异常的聚焦自动化检查与真实注册表验收分开记录。断点暂停期间请求可能超时，恢复后重新操作即可，不放宽认证或网络超时来掩盖失败。

## 验证范围

聚焦配置加载与发现检查：

```bash
./mvnw -pl gateway,saas-forge-services/iam-service -am \
  -Dtest=LocalConfigurationTest,ReservedIamServiceAccessTokenProviderTest,GatewayJwksRouteTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

配置测试经 Spring Config Data 验证显式本地文件模式；内部服务令牌测试覆盖身份、精确 Scope、缓存及失败关闭；Gateway 路由测试继续覆盖发现边界。它们不能代替 IDE 操作、真实 Nacos、数据库及浏览器联调。相关模块完整测试与集成检查可用 `./mvnw -pl gateway,saas-forge-services/iam-service -am verify`；仓库完整流水线由 CI 承担。

验收记录须分别标明自动检查、IDE 断点/重启、Platform 登录刷新、IAM 端口变化、无健康目标及未执行项，不能用配置加载或进程启动成功代替真实认证成功。
