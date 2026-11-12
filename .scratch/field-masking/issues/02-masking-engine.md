# 02: 脱敏引擎与策略库

**What to build:** 按 [spec](../spec.md) 的“实现决策”，在 SDK 提供固定策略库与统一脱敏引擎，供导出、日志、在线三条通道共用。

**Blocked by:** 01

**Status:** resolved

- [x] 策略库支持 `mask_middle`、`mask_tail4`、`keepFirst(n)`、`replace`、`email`、`phone`、`idcard`、`bankcard`、`name`
- [x] 引擎提供校验与执行两个入口；非空值执行后未改变时明确失败，不把未生效当成脱敏成功
- [x] 不支持表达式或正则；未知策略、非法参数在保存或启动时失败
- [x] 预览入口对样例值执行同一引擎，返回脱敏结果与失败原因
- [x] public API 带文档注释，按 semver 管理
- [x] 测试覆盖：每种策略、空值、短值、未改变失败、未知策略

## Comments

## Answer

- 固定引擎与规则模型位于 `backend/forge-sdk/forge-sdk-security/src/main/java/io/saasforge/sdk/security/`：`MaskingEngine.validate/apply/preview`、`MaskingRule`、`MaskingPreview` 与 `MaskingErrorCode`。保存、启动与三条执行通道共用同一校验和算法；异常仅包含固定错误码，不包含原文、规则参数或嵌套原因，预览失败时 `value` 为 null。
- 具体策略口径已写入 `MaskingEngine` 的中文 public Javadoc，包含九种输入输出示例。`mask_middle` 保留首尾各一位，`mask_tail4` 保留末四位，`keepFirst` 保留指定非负码点数，`replace` 使用非空白文本，`email` 保留本地部分首位与域名，`phone`/`idcard`/`bankcard` 分别保留前 3/6/4 位与末 4 位，`name` 保留首位。固定保留范围覆盖整个短值时全部掩码；邮箱结构不完整时全部掩码；`keepFirst` 覆盖整值或任何非空结果未改变时失败。字符长度统一按 Unicode 码点计算。
- `EffectiveFieldPolicy(path, grade, rule, exportAllowed)` 与 `FieldMaskingPolicyProvider.policies(UUID)` 是供后续通道复用的小端口：不带 IO/Jackson/Logback 类型；提供方返回不可变 Map 快照，保留已声明但缺规则的记录。路径/分级沿用 Manifest 的统一校验，不新增 IAM 客户端或执行通道。
- API 为兼容新增，继续使用当前未发布 `0.2.0-SNAPSHOT`，已记录在 `CHANGELOG.md`；未新增必配项或业务编码规则，无需脚手架配置变更。
- TDD 按策略与边界逐个红→绿，日志保留在 `/private/tmp/field-masking-02-{red,green}-*.log`。最终验证：从 `backend/` 执行 `./mvnw -o -pl forge-sdk/forge-sdk-security -am test -Dtest=MaskingEngineTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false` 成功，纯引擎 55 项、security 架构 1 项，reactor 内 core/web 架构各 1 项全部通过；`./mvnw -o -pl forge-sdk/forge-sdk-security spotless:check` 与 `git diff --check` 通过。日志分别为 `/private/tmp/field-masking-02-final-tests.log`、`/private/tmp/field-masking-02-format-check.log`。模块全量回归由集成任务统一执行，端到端通道验收留给工单 04/05。
