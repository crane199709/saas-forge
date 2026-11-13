# 05: 在线脱敏与 demo 验收

**What to build:** 按 [spec](../spec.md) 的“实现决策”，在线接口通过 Jackson 序列化器按注解脱敏；demo-service 提供可验收的字段与受控原文查看示例。

**Blocked by:** 02, 03

**Status:** resolved

- [x] 序列化器只处理 Manifest 声明的字段；嵌套对象与集合中的字段同样生效
- [x] 缺规则时整次响应失败；未声明字段保持原样
- [x] demo-service 增加敏感字段示例（如手机号、身份证），演示导出、在线与日志三种通道
- [x] demo-service 提供一个专用“查看原文”操作，使用独立 Permission Code 并写审计
- [x] 测试覆盖：HTTP 响应脱敏、缺规则 500、原文查看权限与审计、未声明字段不变
- [x] ArchUnit 规则：业务代码不得自行拼接掩码字符串绕过统一引擎

## Comments

Claimed by: ticket05 (codex/field-masking-05)

- 在线与架构切片已验证：FieldMaskingHttpTest 5、FunctionalPermissionTest 2、ForgeArchitectureRulesTest 62、FieldMaskingRuntimeTest 4；DataSecurityTest 的真实 IAM + SDK 策略消费交叉场景通过。导出/日志示例与最终验收待工单 04 接入。


## Answer

已实现统一 Jackson 3 在线通道、demo 三通道示例、受控原文查看与共享架构规则。交付前已合并并再次确认包含最新集成分支 `4460b5a`（01–04 resolved）。

### 实现结果

- security 通过独立 `FieldMaskingJacksonAutoConfiguration` 导出 JacksonModule，不新增对 manifest、audit、web SDK 的生产依赖。只处理带 `@SensitiveField` 的 String 属性，并严格匹配 IAM Manifest 中的路径与分级；嵌套对象、集合、null 均校验规则，未注解字段保持原样。缺提供方、缺字段、缺规则或执行失败均失败关闭，异常不保留原值及嵌套 cause。
- `FieldMaskingResponseAdvice` 在 MVC 选中 Jackson JSON 转换器后启用响应缓冲，转换器按自身 mapper、媒体类型、声明类型、view/filter hints 与 writer 定制完成真实序列化；统一 serializer 将一次读取的不可变快照保存在本次 SerializationContext，完整缓冲成功后才提交字节，不再查规则或执行引擎。EnvelopeResponseAdvice 显式先填状态与 traceId。真实 HTTP 的 64KB 前缀后敏感字段失效时仍返回完整 500 信封，无部分 JSON 或原文。
- demo 共享 `MaskedProfile` 的手机号、身份证声明供在线与 Excel 使用，避免重复登记；含原值 DTO 的 toString 为固定安全文本。`GET /masking/profiles`、`POST /masking/profiles/export`、`POST /masking/profiles/log` 分别展示在线、正式 Excel 管线与 SensitiveLogValue 日志包装。导出使用独立 `masking:export`，普通查看与日志使用 `masking:read`。Manifest 从 7 升 8，运行时复用本服务的 Manifest IAM 地址和凭证；E2E 地址覆盖同步。
- `GET /masking/profiles/raw` 返回独立未注解 RawProfile，经 service 的 `masking:viewRaw` 授权与 `profile.viewRaw` 审计；成功、拒绝均写审计且没有原值。持有原文权限也不会改变普通查看的脱敏结果。
- ArchUnit 拒绝业务自有 Jackson serializer、JsonSerialize 的 serializer/converter 覆盖，以及常见重复掩码字面量与截取/替换/重复的组合；SDK 实现豁免，普通 replace/repeat 与仅调整 JsonSerialize typing 的行为允许，均有正反例。业务规范、平台内部说明、archetype、sub-app-kit、服务指南、CHANGELOG 同步。

### 验证证据

- TDD：首个真实 HTTP 场景先以 404 失败，日志入口也先以 404 失败，接入后转绿；证据 `/private/tmp/masking05-red.log`、`/private/tmp/masking05-log-red.log`。
- 合并后综合专项通过：FieldMaskingHttpTest 6、FunctionalPermissionTest 2、ObjectStorageTest 新增真实 demo 导出 1、FieldMaskingRuntimeTest 4、ForgeArchitectureRulesTest 62、DataSecurityTest 的真实 IAM + SDK 消费场景 1，以及 19 个相关模块 ArchitectureTest。命令：`cd backend && ./mvnw -o -pl examples/demo-service,forge-iam -am test '-Dtest=FieldMaskingHttpTest,FunctionalPermissionTest,ObjectStorageTest#demoHttpExportUsesItsCollectedManifestAndFailsWithoutAResultWhenARuleIsMissing,ForgeArchitectureRulesTest,FieldMaskingRuntimeTest,ArchitectureTest,DataSecurityTest#tenantOverridesAreIsolatedAndInternalPoliciesBelongToAuthenticatedService' -Dsurefire.failIfNoSpecifiedTests=false`，日志 `/private/tmp/masking05-final.log`。
- 在线覆盖嵌套集合、普通字段不变、64KB 大响应末尾缺字段/缺规则/引擎未改变/IAM 不可用四种完整 500、null 缺规则、一次快照与下次请求配置变化，以及独立原文权限与安全审计。
- demo 导出经真实 HTTP 提交、PostgreSQL/Redis/S3 任务管线、下载工作簿并检查单元格，手机号和身份证与在线结果一致；缺规则时任务 FAILED、resultAvailable=false、下载为 io.resultUnavailable，失败审计不含原值。策略边界从真实 ResourceManifestCollector 收集生产功能包声明生成，未重列路径。
- 日志通过真实 HTTP 与实际 Logback `%message` encoder/appender 捕获最终文本，首次未就绪安全占位，异步快照就绪后两字段与在线/Excel 一致，普通说明保留且全过程无原值。三通道初次综合 9 项通过，日志 `/private/tmp/masking05-channels.log`。
- DataSecurityTest 通过公开自动配置取得真实 SDK FieldMaskingPolicyProvider，消费真实 IAM HTTP/JSON，验证租户覆盖隔离、L4 禁导出及其他服务凭证看不到本服务字段，补齐两端各自替身之外的契约证据。
- 最终八个触及 Maven 模块 Spotless 检查与 git diff --check 通过，日志 `/private/tmp/masking05-spotless.log`；sub-app-kit create.test.ts 的 2 个真实工程生成用例和模板 Prettier 检查通过。先前已执行 demo reactor `-am install -DskipTests`，所有专项用 `-am` 直接消费本树源码。

### 验证边界

在线完整 JSON 字节缓冲占用与响应大小成正比的内存，列表应分页；仅处理 Jackson JSON 转换器，文件下载与流式响应不经过此通道。静态规则不能证明任意计算的掩码正确，仍须验证真实输出。完整 IAM、Shell、网关与 demo 部署联调未在本工单运行；受影响模块全量回归由集成分支统一执行。日志的命名/包装支持边界与一秒收敛约定沿用工单 04，不扩展为任意日志文本识别。

### 双轴审查与全量回归修复

- 修复在线预转 JSON 树丢失真实转换器上下文：保留转换器和 advice 链，仅在 MVC 选中 JSON 后启用 Servlet 字节缓冲，完整序列化成功后提交。视图、动态过滤 hints、声明集合多态类型、专用 mapper/媒体类型和 writer 定制均保留。失败先由 DispatcherServlet 清空缓冲，再把不携带原始 cause 的转换错误交给应用现有异常解析器；文件、流式与异步 JSON 的既有行为已验证。
- `FieldMaskingSerializationHttpTest` 在公开 MVC HTTP 转换边界先复现 3/3 红灯（view/filter 隐藏字段泄露、声明集合丢多态标识），修复后 6/6 通过，含转换器精确类型匹配、专用 mapper/writer、二进制/流式与 Callable JSON。证据：`/private/tmp/field-masking-review-serialization-red.log`、`/private/tmp/field-masking-review-final-tests.log`。
- 全量回归暴露的导入 403 来自测试夹具：新增 demo 导出用例复用固定 Membership/版本，把缓存权限变成仅允许敏感字段查看和导出；后续用例虽换租户并重写 Redis，MembershipGrantStore 的一分钟 Membership/版本缓存仍命中。仅将新增用例的在线、导出、任务与下载改为独立 Membership，不修改生产缓存或授权。整类 `ObjectStorageTest` 先以相同导入 403/NPE 失败（51 项中 1 error），修复后完整 51 项通过；证据：`/private/tmp/field-masking-review-storage-red.log`、`/private/tmp/field-masking-review-final-tests.log`。
- Standards 的 3 项 P3 已修复：共用 `MaskingPreviewRequest` 移入 model，其他请求嵌在所属 Controller 并使用 Request 后缀；保留 JSON、Bean Validation 与预览安全 toString。长 SQL 按子句分行；不必要的全限定类型改显式 import。
- 最终专项命令：`cd backend && ./mvnw -o -pl examples/demo-service -am test '-Dtest=FieldMaskingSerializationHttpTest,FieldMaskingHttpTest,ObjectStorageTest,FieldMaskingRuntimeTest,FunctionalPermissionTest,ForgeArchitectureRulesTest,ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false`，全部通过：序列化 HTTP 6、真实 demo HTTP/日志 6、完整存储 51、Runtime 4、权限 2、共享规则 62、18 个模块架构检查。IAM 模型/SQL 变更另经 `DataSecurityTest` 9 与 IAM ArchitectureTest 通过（`/private/tmp/field-masking-review-regression.log`）。受影响模块 Spotless 与 git diff --check 通过；最终全仓 verify 由集成分支执行。
- 仅更正规范与接入指南的实现说明（JSON 树改为实际转换器字节缓冲）及 CHANGELOG，行为规范、SDK 必配项与前端约定未新增；archetype/sub-app-kit 现有摘要不涉及 JSON 树，无需额外改动。
