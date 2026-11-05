# 02: iam 的租户字典与参数管理

**What to build:** 按 [spec](../spec.md) 第 3、6、7 节，在 iam 新功能包 `referencedata` 中提供租户对 Extensible Dictionary 与可修改 Parameter 的修改、成员可读的字典接口、变更事件与租户清理。

**Blocked by:** 01

**Status:** resolved

- [x] 新增权限码 `iam:reference-data:manage`（Tenant Admin 预置拥有）
- [x] 自定义项：租户填写编码、系统加 `custom.` 前缀、「租户 + 字典」内唯一、不可修改；只能停用不能删除
- [x] 内置项：停用或启用、排序、改显示名；编码不能改
- [x] 可选项显示名在「租户 + 字典」内唯一（改名、新增、启用时检查）
- [x] 固定字典只读；只有 `tenantEditable` 的参数可覆盖、值须满足约束，可恢复默认
- [x] 合并后的字典接口对当前 Tenant 全部成员开放，含停用与下线项并带标记；参数只在管理接口返回
- [x] Manifest 新版本删除的内置项按「不再可选」返回；删除的参数的覆盖值保留但不生效
- [x] 修改后经 Outbox 发出 `ReferenceDataChanged`（带租户）
- [x] 租户删除时逻辑删除该租户的自定义项与覆盖值
- [x] 审计 `dictionary.itemCreate/itemUpdate/itemDisable/itemEnable`、`parameter.override/reset`
- [x] 表带标准列与租户隔离（RLS），手写 SQL 只在 mapper XML
- [x] 测试覆盖：前缀与唯一性、名称冲突、固定字典与不可改参数被拒、约束校验、事件发出、无权限 403

## Comments

**租户隔离不用 RLS（与清单措辞不同，2026-11-04 决定）**

- 新表 `dictionary_item`、`parameter_override`（V20）沿用 ADR-0001 的 iam 例外：不启用 RLS，每条 mapper XML 语句显式写 `tenant_id = #{tenantId}`，Mapper 带 `@InterceptorIgnore(tenantLine = "true")`，与 `extended_attribute` 等 iam 表一致。iam 从不设置 `app.tenant_id`，启用 RLS 会让所有查询读不到行。标准列、逻辑删除与 `WHERE NOT deleted` 部分唯一索引照常。

**实现备注**

- 默认值只来自清单，租户只在修改或自定义时才有行：内置项的设置行 `name`、`sort_order` 为 NULL 表示沿用声明；自定义项编码为 `custom.` 加租户填写的部分（`[A-Za-z0-9][A-Za-z0-9._-]{0,56}`，总长不超过 64）。
- 排序：内置项缺省为声明位置（从 1 起），新自定义项为当前最大排序加 1；租户以整数 `sort` 调整。字典项按「下线项在最后 → sort → 编码」排列。
- 下线项：iam 保存了各服务的全部清单版本，字典接口把旧版本声明、当前版本删除的内置项作为 `retired` 返回（名称取最后一次声明）。整个字典被删除时不再返回该字典。字典由可扩展改为固定时，租户设置不再生效，已有自定义项变为 `retired`。下线项不能修改或启用（`dictionary.itemNotFound`）。
- 名称唯一只在可选项（`enabled`）之间检查；改名只在该项可选时检查，已停用项改名后在启用时检查。同一租户同一字典的修改用事务级咨询锁串行。
- 覆盖值只在参数仍可修改且仍满足当前声明时生效，否则返回默认值（服务新版本收紧约束或取消可修改后，行保留但不生效）。`dictionary` 类型的覆盖值须为该字典在本租户的可选项，可以是自定义项。值的类型与约束校验复用 SDK 新增的 `ResourceManifest.Parameter.requireValue(Object)`，与默认值的校验同一套规则。
- `ReferenceDataChanged(tenantId)` 放在 `forge-sdk-provisioning`，新 Topic `forge-reference-data`（已加入 `deploy/rocketmq/create-topics.sh`），只在修改实际发生时发布；恢复默认而没有覆盖值时不发。
- 审计类别为 `operation`（业务操作），`resourceType` 为 `dictionaryItem` / `parameter`。
- 给 03：`ReferenceDataService.dictionaries(tenantId)` 与 `parameters(tenantId)` 返回合并后的生效值且不校验权限，内部接口只需在 controller 中包一层。

**评审发现的规格空白（2026-11-05，仅记录，未实现，已写入 spec §10 待定）**

- 清单新版本新增的内置项可能与租户的自定义项或改过名的内置项同名，产生重名的可选项，按名称导入会有歧义；名称唯一目前只在租户新增、改名、启用时检查。
- `dictionary` 类型参数的默认值可能指向租户已停用的项：覆盖值失效时回落到默认值，而默认值不检查是否可选，参数读取仍把它作为生效值返回。

**后续修改（2026-11-05，用户批准，分支 feat/refdata-followups）**

- 上面两条规格空白已决定并实现，写入 spec §3.1：
  - 停用占用项：字典项是本服务某个 `dictionary` 类型参数的生效值（有效覆盖值，或没有有效覆盖值时的默认值）时停用返回 409 `dictionary.itemInUse`，不可修改的参数也计入；文案点名参数，参数可修改时提示先修改取值。检查在该字典的咨询锁内进行。
  - 重名：合并时不自动处理，字典接口与内部接口给与其他可选项重名的可选项标记 `nameConflict`，租户改名其中之一后标记消失；tenant-app 字典页标出并提示改名。导入（data-import-export）对名称匹配到多个可选项的行报错。
- `ReferenceDataChanged` 事件、它的 Outbox 发布与 Topic `forge-reference-data` 已删除（没有其他订阅方）。改为在修改事务提交后（`afterCommit`）以 `INCR` 递增 Redis 键 `iam:{tenant_id}:reference-data:version`（`ReferenceDataKeys.version`，在 forge-sdk-core），Redis 失败只记日志，由业务服务的缓存过期兜底。被拒绝的修改与没有覆盖值时的恢复默认不递增。
- 已知余留（未处理，待定）：参数覆盖为其他项后，默认值指向的项不再是生效值，可以停用；之后恢复默认（删除覆盖值）不检查默认值是否可选，生效值又会是停用项。本次之前已停用的默认值同样不受影响。
