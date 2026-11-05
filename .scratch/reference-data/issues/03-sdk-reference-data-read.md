# 03: SDK 读取字典与参数

**What to build:** 按 [spec](../spec.md) 4.1 节，业务服务经 SDK 读取当前租户的生效字典与参数，进程内缓存、事件失效、过期兜底，并提供 `@DictionaryValue` 校验。

**Blocked by:** 02

**Status:** resolved

- [x] iam `/internal/` 接口返回某租户合并后的生效值（网关拒绝 `/internal/`）
- [x] SDK 门面：按编码读取字典（可选项与全部项）与参数（按声明类型返回）
- [x] 按租户的进程内缓存；收到 `ReferenceDataChanged` 后清除该租户缓存；过期时间兜底（参考 10 分钟，可配置）
- [x] Bean Validation 注解 `@DictionaryValue("<字典编码>")`：编码须为当前租户该字典的可选项
- [x] 新增模块或放入既有模块时不得产生 SDK 循环依赖（`forge-architecture` 规则通过）
- [x] demo-service 声明字典「客户来源」（可扩展）与参数 `order.auto-close`，订单写入使用 `@DictionaryValue`
- [x] 测试覆盖：事件到达后读到新值、漏收事件时过期后读到新值、停用项校验失败

## Comments

**每个实例都清除缓存：每实例独占消费组（2026-11-05 决定）**

- 服务的 `EventHandler` 共用一个消费组（集群消费），一条消息只到一个实例；所用 gRPC 客户端 rocketmq-client-java 5.x 不支持广播消费。因此 `forge-sdk-referencedata` 在每个实例启动时另建一个 PushConsumer，消费组为 `<spring.application.name>-reference-data-<随机 UUID>`，只订阅 `forge-reference-data` 的 `ReferenceDataChanged`，各组都收到全部消息，效果即广播。
- 清除缓存幂等，不经 SDK 的 `forge_consumed_message` 去重，也不开事务；消息体解析失败只记日志并确认，由缓存过期兜底。
- 代价：Broker 须允许自动创建订阅组（`autoCreateSubscriptionGroup`，默认开启）；实例退出后遗留同前缀的空消费组，需运维定期清理（已写入 docs/service-onboarding-checklist.md）。`forge.mq.enabled=false` 时不订阅，只靠过期刷新。
- `OutboxMessagingTest.referenceDataChangedEvictsTheTenantsCacheOnEveryInstance` 以第二个 Spring 上下文模拟同一服务的另一实例，经真实 RocketMQ 验证两个实例都读到新值。

**实现备注**

- 新模块 `forge-sdk-referencedata`（包 `io.saasforge.sdk.referencedata`），依赖 forge-sdk-provisioning（事件契约，传递得到 mq）、spring-web、jakarta.validation-api；无循环依赖，模块 ArchitectureTest 通过。
- iam 内部接口 `GET /internal/reference-data/{tenantId}`（`manifestSecurity` 链、上报令牌认证，主体即服务名），返回 `{dictionaries, parameters}`：字典为全部服务的（支持 `服务:编码` 跨服务引用），参数只返回调用方服务自己的（含不可修改的参数，取生效值）。网关既有规则拒绝 `/iam/internal/**`。
- 配置 `forge.reference-data.{iam-uri, token, cache-ttl}`：地址与令牌无默认值、漏配启动失败，令牌即 iam `forge.iam.manifest.tokens` 中该服务的值；`cache-ttl` 默认 10m。
- 门面 `ReferenceData`：`items` / `selectableItems` / `isSelectable`（字典编码不带前缀即本服务），`string/integer(long)/decimal/duration/boolean/dictionaryParameter`（类型不符或未声明抛 IllegalArgumentException）。iam 不可用时抛 503 `system.unavailable`。缓存项移出 Map 即失效，事件之前开始的拉取不会把旧值写回缓存。
- `@DictionaryValue` 校验器由 Spring 约束校验器工厂注入 `ReferenceData`，null 视为未填写；字段错误码 `validation.dictionaryValue`，文案加在 SDK `forge-messages.properties`。
- demo-service：清单 version 5→6，声明字典 `customer-source`（可扩展：online、referral）与参数 `order.auto-close`；订单新增可空列 `customer_source`（V10），创建与修改请求体 `@Valid` + `@DictionaryValue("customer-source")`，响应只返回编码。测试以本地 `HttpServer` 模拟 iam 内部接口（`TestReferenceData`）。

**缓存失效改为 Redis 版本号（2026-11-05，用户批准，分支 feat/refdata-followups）**

- 取代上面「每实例独占消费组」：`ReferenceDataChangedListener`、每实例消费组与 Topic `forge-reference-data` 已删除，运维不再需要清理遗留消费组。
- 沿用 ADR-0005 的做法：iam 在修改提交后递增 `iam:{tenant_id}:reference-data:version`（`ReferenceDataKeys`，forge-sdk-core，全局键，不走租户前缀模板）；`ReferenceData` 每次读取先经 Spring Boot 的 `StringRedisTemplate` 读这个键（一次 GET），与缓存时记下的值不同即重新拉取。先读版本再拉取，拉取期间又有修改时下次读取会再拉一次。
- 兜底：键缺失（未修改过或被淘汰）时只靠 `cache-ttl`（默认 10 分钟）；读 Redis 失败时记日志并沿用未过期的缓存；服务没有 `StringRedisTemplate` 时只靠过期。
- 模块依赖由 forge-sdk-provisioning 改为 forge-sdk-core + spring-data-redis。SDK `ReferenceDataTest` 以真实 Redis 验证 iam 递增版本后（没有任何事件）读到新值、没有版本号时过期后读到新值；iam `ReferenceDataTest` 验证修改递增本租户版本、被拒绝的修改不递增。demo 的双实例 RocketMQ 测试随之删除。字典项新增的 `nameConflict` 字段 SDK 不读取（`TenantDictionaryItem` 忽略未知字段）。
