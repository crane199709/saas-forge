# 字典、参数与编号规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准（「参考数据」一节）。本规格的决策经 2026-11-04 的设计拷问逐条确认（Q23–Q41），未单独记录 ADR。

## 1. 背景与目标

Business Service 需要下拉选项、可由租户调整的设置项和可读的业务编号，目前 SDK 与平台都没有提供。规格的开通流程提到初始化「字典」，`forge.tenant.ignore-tables` 的注释也以全局字典为例，但都没有实际能力。

目标：

- Business Service 声明 Dictionary 与 Parameter，Tenant 可扩展 Extensible Dictionary、覆盖可修改的 Parameter，业务服务经 SDK 读取生效值并校验取值。
- Business Service 按本地声明的 Numbering Rule，在业务事务内生成连续不跳号的 Business Number。

非目标：

- 树形字典、多语言显示名、JSON 或列表类型的参数。
- 平台（Operator）层面的默认值修改或按租户代改。
- Tenant 修改 Numbering Rule、租户级时区。
- 后端响应中自动补字典名称（由前端翻译）。

## 2. 声明

### 2.1 Dictionary 与 Parameter：写进 Resource Manifest

在 `META-INF/forge/manifest.json` 中新增两节，随 Resource Manifest 上报到 iam；内容变化须升版本号，沿用同版本不同内容即 409 的规则。

```json
{
  "dictionaries": [
    { "code": "customer-source", "name": "客户来源", "extensible": true,
      "items": [ { "code": "online", "name": "线上" }, { "code": "referral", "name": "转介绍" } ] }
  ],
  "parameters": [
    { "code": "order.auto-close", "name": "未支付订单自动关闭时长", "type": "duration",
      "default": "PT30M", "min": "PT5M", "max": "P3D", "tenantEditable": true }
  ]
}
```

- 编码在服务内唯一；跨服务由 iam 以「服务 + 编码」区分（具体的跨服务引用方式在工单中确认）。
- 内置项编码不能以 `custom.` 开头（校验在 SDK 生成与 iam 接收时共用）。
- Parameter 类型：`string`（可设 `maxLength`）、`integer`、`decimal`、`duration`（三者可设 `min`/`max`）、`boolean`、`dictionary`（须指明字典，取值为其一个有效项）。默认值必须满足约束。

### 2.2 Numbering Rule：只在业务服务本地声明

不上报 iam。格式由三段组成：固定前缀；可选日期段（`yyyy` / `yyyyMM` / `yyyyMMdd`）；序号段（最小位数，不足补零，超出时位数继续增长，不报错）。计数周期跟随日期段：无日期段永不重置，`yyyy` 按年、`yyyyMM` 按月、`yyyyMMdd` 按天。

## 3. 租户可做的修改（存 iam，新功能包 `referencedata`）

### 3.1 Extensible Dictionary

- 新增自定义项：租户填写编码，系统加 `custom.` 前缀；编码在「租户 + 字典」内唯一，创建后不可修改。
- 内置项：可停用或启用、调整排序与显示名，编码不能改也不能删。
- 自定义项只能停用，不提供删除：业务数据保存编码，iam 无法知道各服务是否仍有引用，且编码不得被重新使用。管理界面默认隐藏已停用项，可切换显示。
- 固定字典对 Tenant 只读。
- 同一租户、同一字典内，可选项的显示名不能重复（导入按名称匹配字典项，见 [data-import-export](../data-import-export/spec.md)）：内置项名称在声明时校验；租户修改内置项显示名、新增自定义项、启用已停用项时，与现有可选项重名即拒绝。

### 3.2 Parameter

- 只有 `tenantEditable` 的参数可覆盖，值须满足声明的类型与约束；可恢复默认值（删除覆盖）。
- Manifest 新版本删除某参数时，租户的覆盖值保留但不再生效。

### 3.3 不再可选的字典项

租户停用的内置项、新版本 Manifest 删掉的内置项、停用的自定义项，都不在可选列表中，新写入的校验不通过；字典接口仍返回它们并带「已停用」标记，供历史数据显示名称。

## 4. 读取

### 4.1 SDK（业务服务）

- 从 iam `/internal/` 接口拉取某租户合并后的生效值（内置项 + 自定义项、默认值 + 覆盖值），按租户缓存在进程内。
- iam 在租户修改后经 Outbox 发出 `ReferenceDataChanged`，各实例收到后清除该租户的缓存；缓存另设过期时间兜底（参考 10 分钟）。
- 提供 Bean Validation 注解（如 `@DictionaryValue("customer-source")`），校验编码属于当前租户该字典的可选项。
- 接口响应只返回编码，不补名称。

### 4.2 前端

- 字典接口对当前 Tenant 的全部成员开放，不需要权限码，供前端翻译名称（带缓存）。
- Parameter 不对前端直接开放，只出现在管理页；前端需要的参数值由业务接口带出。

## 5. Business Number

- SDK 提供计数表与取号接口，计数存在业务服务自己的 schema，按「租户 + 规则 + 计数周期」一行。
- 取号是对计数行加锁递增，与业务数据在同一本地事务中完成；事务回滚时号一起回滚，因此连续不跳号。同一规则的并发取号在这一行上排队。
- 日期段按 Business Time Zone 计算，配置项 `forge.business-zone` 由 `forge-sdk-core` 提供（定时任务的 cron 共用，见 [tenant-scheduling](../tenant-scheduling/spec.md)），放在 Nacos 共享配置中，代码不设默认值，使用编号的服务漏配时启动失败。
- 取号不记审计。

## 6. 权限、开通与清理

- 管理字典与参数需要新增权限码 `iam:reference-data:manage`，Tenant Admin 预置拥有。
- 开通时无需初始化：默认值来自 Manifest，租户只在覆盖或自定义时才有数据。
- 租户删除时：iam 逻辑删除该租户的自定义项与覆盖值；业务服务中的计数行由 SDK 自带的 `TenantDataCleaner` 逻辑删除。

## 7. 审计事件

`dictionary.itemCreate`、`dictionary.itemUpdate`、`dictionary.itemDisable`、`dictionary.itemEnable`、`parameter.override`、`parameter.reset`。

## 8. 前端（tenant-app）

租户管理新增「字典」与「参数」页，需 `iam:reference-data:manage`：

- 字典：按服务、字典列出；可扩展字典可新增自定义项、停用或启用、排序、改显示名；默认隐藏已停用项。
- 参数：列出可修改的参数及当前值、默认值与约束，可覆盖或恢复默认。

## 9. 验收

- 声明校验：内置项使用 `custom.` 前缀、内置项名称重复、默认值违反约束、`dictionary` 类型引用不存在的字典时，生成与上报都失败。
- 租户改名、新增或启用字典项导致可选项重名时被拒绝。
- 租户修改后，业务服务在事件到达后读到新值；漏收事件时在缓存过期后读到新值。
- 停用项不可选、写入校验失败，但字典接口仍返回并带标记。
- 并发取号连续不重复；事务回滚不产生空号；跨计数周期按 Business Time Zone 重置；超出位数继续增长。
- 漏配 `forge.business-zone` 时使用编号的服务启动失败。
- 租户删除后，自定义项、覆盖值与计数行被逻辑删除。

## 10. 待定

- 参考数据集中在 iam 使 iam 超出身份与权限的范围；参考数据增多时再拆分为独立服务。
- 服务新版本新增的内置项可能与租户已有的自定义项或改过名的内置项同名，造成可选项重名，按名称导入时无法确定对应哪一项；目前只在租户新增、改名、启用时检查名称唯一。是否在合并时处理（如提示租户改名）待定。
- `dictionary` 类型参数的默认值可能是租户已停用的内置项：覆盖值指向停用项时回落到默认值，但默认值本身不检查是否可选，参数读取仍把它作为生效值返回。是否需要另行处理待定。
