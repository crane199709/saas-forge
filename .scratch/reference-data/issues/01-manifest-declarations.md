# 01: Resource Manifest 声明字典与参数

**What to build:** 按 [spec](../spec.md) 2.1 节，`manifest.json` 新增 `dictionaries` 与 `parameters`，SDK 生成与 iam 接收共用校验，iam 按版本存储。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] `ResourceManifest` 新增字典（编码、名称、`extensible`、内置项）与参数（编码、名称、类型、默认值、约束、`tenantEditable`）的 record
- [ ] 参数类型 `string`（`maxLength`）、`integer`、`decimal`、`duration`（`min`/`max`）、`boolean`、`dictionary`（须指明字典）
- [ ] 共用 `validate()`：编码服务内唯一、内置项不以 `custom.` 开头、内置项名称在字典内不重复、默认值满足约束、`dictionary` 类型引用的字典存在
- [ ] 为空时不输出到 JSON，已上报的服务不补写时无需升版本
- [ ] iam 存储新增内容，同版本不同内容仍 409
- [ ] 确定并记录跨服务引用字典的编码写法（服务 + 编码）
- [ ] 测试覆盖每条校验规则在 SDK 生成与 iam 接收两侧都生效

## Comments
