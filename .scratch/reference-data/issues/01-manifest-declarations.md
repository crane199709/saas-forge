# 01: Resource Manifest 声明字典与参数

**What to build:** 按 [spec](../spec.md) 2.1 节，`manifest.json` 新增 `dictionaries` 与 `parameters`，SDK 生成与 iam 接收共用校验，iam 按版本存储。

**Blocked by:** —

**Status:** ready-for-agent

- [x] `ResourceManifest` 新增字典（编码、名称、`extensible`、内置项）与参数（编码、名称、类型、默认值、约束、`tenantEditable`）的 record
- [x] 参数类型 `string`（`maxLength`）、`integer`、`decimal`、`duration`（`min`/`max`）、`boolean`、`dictionary`（须指明字典）
- [x] 共用 `validate()`：编码服务内唯一、内置项不以 `custom.` 开头、内置项名称在字典内不重复、默认值满足约束、`dictionary` 类型引用的字典存在
- [x] 为空时不输出到 JSON，已上报的服务不补写时无需升版本
- [x] iam 存储新增内容，同版本不同内容仍 409
- [x] 确定并记录跨服务引用字典的编码写法（服务 + 编码）
- [x] 测试覆盖每条校验规则在 SDK 生成与 iam 接收两侧都生效

## Comments

**跨服务引用字典的编码写法（2026-11-04 决定）**

- 写作 `服务:编码`，服务为 `spring.application.name`，如 `crm-service:customer-source`；不带服务前缀的编码指本服务的字典。后续 SDK 注解（如 `@DictionaryValue("crm-service:customer-source")`）、iam 内部接口与前端按服务查字典都用这一形式。
- 字典与参数编码限定为 `[a-z][a-z0-9._-]{0,63}`，不含冒号，因此第一个冒号即为分隔符，不会与编码混淆；服务名同样不含冒号。
- Manifest 内 `dictionary` 类型参数只能引用本清单声明的字典（只写编码，不接受 `服务:编码`）：SDK 生成时看不到其他服务的清单，无法校验引用是否存在，各服务上报顺序也不确定。

**实现备注**

- 记录类型 `ResourceManifest.Dictionary` / `DictionaryItem` / `Parameter`。参数取值在 JSON 中随类型书写：`string`/`duration`/`dictionary` 为字符串（`duration` 用 ISO-8601，如 `PT30M`），`integer`/`decimal` 为数字，`boolean` 为布尔值；Java 侧为 `Object`，`integer`/`decimal` 按 `BigDecimal`、`duration` 按 `Duration` 比较。
- `extensible`、`tenantEditable` 缺省为 false；默认值必填。
- 额外校验（防止声明被静默忽略）：约束只能用于适用类型（`min`/`max` 仅 `integer`/`decimal`/`duration`，`maxLength` 仅 `string`，`dictionary` 仅 `dictionary` 类型）、`min` 不大于 `max`、字典内项编码不重复；`dictionary` 类型的默认值须为该字典的内置项。
- 内置项编码 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`，不能以 `custom.` 开头。
- iam 不新增表：字典与参数随清单 JSON 存入 `resource_manifest.content`，同版本内容比较自然覆盖新增两节。
