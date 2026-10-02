# Example Gateway、日志、Trace 与最小审计接入验证

日期：2026-10-02。范围为 MVP 阶段 3 的后端接入切片；工作区基于 `ae8e63f63e198e2a6ad7c5e3eb2cbc1ad08a28c4`，验证时含本轮未提交改动。**本记录是后端诊断与准备结果，Tenant Shell / Remote 浏览器验收未执行，不能据此勾选阶段 3。**

决策见 [ADR 0054](../adr/0054-example-http-contracts-compose-into-route-catalog.md)，运行准备见 [Example README](../../examples/project-service/README.md)。

## 实现范围

- 独立 Example OpenAPI 登记后生成十个公开 operation；Gateway 和 Starter 共享 Route Catalog 并分别验证用户 Token，删除 `ProjectConfiguration.projectRoutes()` 手写声明。构建校验归属、凭据、路径冲突及模块 / Spring / Nacos 标识一致性。
- Gateway 支持 PUT 转发。已批准的最小 CORS 规则仅对 Project 路径及子路径允许 Console Origin 的 If-Match；其他 v1 路径不新增该 Header 权限。
- Gateway、Example、Audit 输出 Schema 白名单 JSON，生成实际 HTTP SERVER、Outbox PRODUCER、Audit CONSUMER Span，并通过官方 OpenTelemetry 集成导出 OTLP。关闭带原始 HTTP URL 的自动观测，保留受控路由模板；异常保留类型与固定失败码，不直接输出框架原文、MDC 或堆栈。
- 六类成功事实与业务变更、幂等结果同事务保存。重放和业务拒绝不追加事实；Outbox 失败回滚业务。发布使用独立领取令牌、Kafka 确认、指数重试与不变 Event ID；Audit 提交去重和只追加记录后才确认。非法 Example Key 不进入持久化失败诊断，非法正文不产生安全快照。
- 只新增前向迁移：Example V9 / V10，Audit V6；原有迁移未修改。Outbox 运行账号只能更新投递状态列，不能改写事实内容。业务资源的 Tenant Context / FORCE RLS 不变。

## 实测与限制

| 检查 | 结果 | 直接证明的边界 |
| --- | --- | --- |
| Example `ProjectHttpIT` + `ProjectContractTest` | 43 项：42 通过，1 跳过 | 真实 HTTP / Starter、PostgreSQL 18 受限账号和 RLS；六类事实、幂等重放不增事件、Outbox 故障回滚、Kafka 失败保留事件、租约过期后旧令牌不能完成新领取。跳过项为需诊断脚本提供制品的链路方法，后续已单独运行通过 |
| Gateway `GatewayJwksRouteTest` | 18 项通过 | 既有路由 / 认证 / Trace 回归，以及 Console 的 Project 版本预检、其他 Origin 拒绝、其他 v1 路径不开放 If-Match。属于 HTTP 测试，不是浏览器 |
| Audit readiness / Validator / FailureHandler | 相关检查通过 | 新消费者受控启用；启用后必须取得分区才 Ready；六类事实白名单；失败不确认、非法 Key / 正文不持久化、有效事实进入独立隔离 Topic |
| Audit `SessionStartedConsumerPostgreSqlKafkaIT` | 8 项通过 | 真实 Kafka 与受限 PostgreSQL，包括 Example 记录提交后确认中断、重投去重、运行账号不能 UPDATE / DELETE 审计记录 |
| `ForgeObservabilityTest` | 2 项通过 | 实际 SDK Span 保留 Trace ID 并产生新 Span ID；框架消息、MDC、键值和异常消息的敏感值不能泄露 |
| 最终隔离链路脚本 | 通过 | 同轮真实 Gateway → Example → Kafka → Audit → Collector，六类写操作产生六条审计记录，核对六条完整 SERVER → SERVER → PRODUCER → CONSUMER 父子链 |
| 本轮真实日志 Schema 校验 | 249 条通过 | Project 75、Gateway 28、Audit 146；含 6 次发布和 6 次审计追加。Draft 2020-12、格式、条件字段、登记事件和敏感值检查；校验器的额外字段 / 缺上下文字段 / 非法时间负向场景被拒绝。Maven / Testcontainers 非应用输出不计入 |
| 路由、服务资格、事件登记、日志策略四项相关质量检查 | 通过 | 包括已批准复用 Swagger Parser 的继承认证语义检查 |
| 完整受影响模块测试命令 | 失败 | 质量模块 22 项中 21 通过，`persistenceArtifactsStayInsideOwningService` 将既有 Git 忽略的 `.scratch/issue-206/history-source` Mapper 当成领域制品。未删除历史文件、未放宽该门禁；被 Reactor 跳过的 Example 已单独补跑通过 |
| 根 `verify` / 远端 CI | 未执行 | 不把局部检查或打包成功写成完整门禁通过 |
| Tenant Shell / Remote、Fresh Compose + 稳定版 Chrome | 未执行 | 未证明实际 IAM 登录、统一 Runtime / 正式类型化 Client、Manifest 启用、Remote CRUD、跨租户拒绝及受信 HTTPS / Cookie / CSRF 浏览器边界 |

链路使用隔离 PostgreSQL / Redis / Kafka / Collector，真实应用代码和本轮构建的 Gateway / Audit 制品；JWKS 与 Simple Discovery 是受控测试夹具，不是生产 IAM 或 Nacos。没有访问已有业务数据库或发布环境配置；诊断子进程和容器结束后清理。临时输出不作为长期证据引用，本记录保留结果、范围和复现入口。

## 复现

在仓库根运行；需要 JDK 17、Docker 和 Maven 所需依赖：

```sh
./mvnw -pl examples/project-service -am test \
  -Dtest='*Test,ProjectHttpIT' -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -pl gateway,saas-forge-services/audit-service -am test \
  -Dtest='GatewayJwksRouteTest,AuditRuntimeReadinessHealthIndicatorTest,AuditConsumerFailureHandlerTest,ExampleFactEventValidatorTest,SessionStartedConsumerPostgreSqlKafkaIT' \
  -Dsurefire.failIfNoSpecifiedTests=false

uv run --no-project --with 'jsonschema[format-nongpl]==4.25.1' \
  bash scripts/verify-example-pipeline.sh
```

jsonschema 是经确认的诊断工具，版本依据 [官方发行记录](https://pypi.org/project/jsonschema/4.25.1/)，不进入应用运行时或 Maven 依赖。可在个人虚拟环境准备相同版本后直接运行脚本。诊断的 JAR 子进程只为测试服务，日常应用启停仍采用 IDE 原生 Run / Debug。

完整受影响模块命令如下；当前工作区会出现表中已记录的历史临时文件扫描失败：

```sh
./mvnw -pl examples/project-service,gateway,saas-forge-services/audit-service,saas-forge-quality-gates -am test \
  -Dtest='*Test,ProjectHttpIT,SessionStartedConsumerPostgreSqlKafkaIT' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

## 部署与最终验收前置

准备 Example 独立数据库并运行 V9 / V10，Audit 运行 V6。按实际环境准备 Example Kafka Topic、生产身份、Audit group / 隔离 Topic 权限；然后受控启用 `saas.forge.audit.example-consumer.enabled=true`。新消费者默认关闭，未启用不能宣称审计投递可用。

Example 必须在联调 Nacos namespace 注册为 `project-service`，有自己的注册身份和 IAM 发现权限；Gateway 身份需该服务的 naming 只读发现权限。现有部署脚本未自动提供这些 Example 身份、Topic 或 ACL；必须通过环境的受控配置 / 权限流程准备。没有更改 Nacos 环境资源、配置 revision、共享资源或 staging/prod 值，也没有以静态下游地址作为运行配置替代发现。

后续按阶段 3 的联合验收清单，通过真实 Tenant Shell / Remote、正式 Client 和受信 HTTPS Gateway 操作 Project / Task，核对成功、跨租户拒绝及恢复路径，同时关联日志、Collector Span 和 Audit 权威结果。API 测试、种子数据和本诊断只作为准备手段。
