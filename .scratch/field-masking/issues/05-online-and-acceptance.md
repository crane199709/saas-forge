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
- `FieldMaskingResponseAdvice` 在 Servlet 写出前一次性形成完整 JSON 树，统一 serializer 将一次读取的不可变快照保存在本次 SerializationContext；发送树时不再查规则或执行引擎。EnvelopeResponseAdvice 显式先填状态与 traceId。真实 HTTP 的 64KB 前缀后敏感字段失效时仍返回完整 500 信封，无部分 JSON 或原文。
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

在线完整 JSON 树占用与响应大小成正比的内存，列表应分页；仅处理 Jackson JSON 转换器，文件下载与流式响应不经过此通道。静态规则不能证明任意计算的掩码正确，仍须验证真实输出。完整 IAM、Shell、网关与 demo 部署联调未在本工单运行；受影响模块全量回归由集成分支统一执行。日志的命名/包装支持边界与一秒收敛约定沿用工单 04，不扩展为任意日志文本识别。
