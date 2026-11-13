# 04: 导出与日志脱敏

**What to build:** 按 [spec](../spec.md) 的“实现决策”，导出管线在写单元格前套用字段策略，日志通过 converter 按字段名/参数名替换原值。

**Blocked by:** 02, 03

**Status:** resolved

- [x] 导出前按敏感字段声明取策略；取不到策略或执行失败时任务失败，不产出明文文件
- [x] 未声明字段保持原样；已声明字段按生效规则脱敏
- [x] 日志 converter 覆盖敏感参数与显式标记的字段；缺规则时丢弃原值并告警，不阻断请求
- [x] 配置变更与导出失败写审计；日志本身不记录原文
- [x] 测试覆盖：导出文件单元格、缺规则失败、未声明字段、日志捕获无明文

## Comments

Claimed by: field-masking-04 agent（2026-11-12）


## Answer

已实现导出与 Logback 适配；分支以集成分支 `2597b6c`（01–03 resolved）为基础，交付前再次确认包含集成最新 tip。公共显式日志接口为 `SensitiveLogValue.of(path, value)`，导出沿用 `ExcelIo.export` 与 record component 上的 `@SensitiveField`。

### 实现结果

- 导出任务内只读取一次不可变租户策略快照，创建临时结果前预检全部列，零行导出同样检查。写单元格前对最终显示文本（含字典翻译）调用统一引擎；未声明列原样。已注解但快照没有登记、缺规则、L4 禁止或执行失败均以 `io.maskingFailed` 结束，无结果对象或下载票据，异常不保留原值和嵌套 cause。导入、模板与错误报告未改变。
- 复用 IoTasks 失败审计。导出成功、失败的审计均只保留任务、行数与错误码等安全元数据，省略可能包含敏感查询值的自由文本摘要；导入保留原有行为。IAM 配置变更审计已由 03 完成。
- EnvironmentPostProcessor 通过 Boot SPI 在日志初始化前登记本地 Manifest 与应用包注解，并真正注册 `%m`、`%msg`、`%message`、`%kvp`。敏感字段扫描下沉至 security，由 manifest 单向复用，security 没有新增 manifest/audit/web 生产依赖，Logback 依赖为 optional。
- 支持完整路径/唯一短名的 `field={}` 或 `field: {}` 命名参数、SLF4J 结构化 key/value，以及显式包装；包装的 `toString()` 永远为 `[redacted]`。异步日志只读事件捕获的 tenant_id MDC。首次、过期、规则缺失、执行失败或 IAM 不可用时丢值并写非递归 status 告警，不阻断业务。日志独立一秒有效快照异步刷新，有效期从 HTTP 请求开始计算；慢响应不能延长旧策略。应用关闭释放工作线程、快照和提供方，仅保留安全字段路径用于停机日志失败关闭。
- 已同步业务服务规范「敏感字段导出与日志」、archetype 配置和 AGENTS、sub-app-kit 生成摘要、子应用规范、业务服务指南与 CHANGELOG。明确日志最多一秒配置收敛与首次可能丢值，不声称与无缓存导出/在线通道相同。SDK API 维持未发布 `0.2.0-SNAPSHOT`，旧服务无新增必配项。

### 验证证据

- TDD 红灯：真实 S3 下载工作簿原先输出 `13812345678`，适配后输出 `138****5678`；成功和失败审计在故意含手机号的 querySummary 下均先复现原文泄漏，再移除导出审计自由文本使其转绿；关闭应用后命名日志曾因清空声明回退原文，保留安全路径字符串后转绿。日志分别见 `/private/tmp/masking04-export-red.log`、`/private/tmp/masking04-export-audit-red.log`、`/private/tmp/masking04-log-close-red.log`。
- 直接相关 reactor 检查通过：FieldMaskingLogTest 3、FieldMaskingRuntimeTest 3、ResourceManifestCollectorTest 58、ObjectStorageTest 新增 4。命令：`cd backend && ./mvnw -o -pl examples/demo-service,forge-sdk/forge-sdk-security,forge-sdk/forge-sdk-manifest -am test '-Dtest=FieldMaskingLogTest,FieldMaskingRuntimeTest,ResourceManifestCollectorTest,ObjectStorageTest#sensitiveExport*+exportExecutionFailureProducesNoFileAndAuditsOnlySafeMetadata+exportUsesOneSnapshotAndNextTaskObservesConfigurationChanges' -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.skip=true`。日志 `/private/tmp/masking04-focused.log`。
- 导出覆盖实际文件单元格、未声明列、缺规则/无登记/L4 的零行失败无文件、运行失败无文件与安全审计、任务快照一致及下一任务新规则。使用真实 PostgreSQL、Redis、S3 兼容容器与 SDK IAM HTTP 客户端；IAM 使用进程内 HTTP 边界替身。
- 日志捕获真正 encoder/appender 的最终文本：本地 Manifest/注解启动窗口、三个消息别名与 kvp、真实 Boot SPI 激活（先恢复 Logback 原生 converter）、显式包装、AsyncAppender 捕获 MDC、就绪规则结果、过期即时丢值/非阻塞、缺规则、IAM 不可用、非递归告警与应用关闭后安全输出。最终日志 `/private/tmp/masking04-logs-final.log`。
- security/manifest/io 三个 ArchitectureTest（含依赖方向与包无环）通过；日志 `/private/tmp/masking04-architecture.log`。触及的五个 Maven 模块 Spotless 检查与 git diff --check 通过；sub-app-kit create.test.ts 2 个生成用例及模板 Prettier 检查通过。先执行了当前 demo reactor `-am install -DskipTests`，日志 `/private/tmp/masking04-install.log`。

### 验证边界

本工单未运行连接真实 IAM、Shell、网关与业务服务的完整部署联调。在线/示例三通道整合由 05 完成，受影响模块全量回归由集成分支统一执行。日志自动识别仅覆盖文档定义的命名/显式包装/结构化参数契约；任意拼接消息、无名称位置参数、整个 DTO、异常内容或自定义 JSON encoder 由调用方提供安全参数。
