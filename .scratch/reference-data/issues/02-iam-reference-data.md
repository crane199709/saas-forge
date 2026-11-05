# 02: iam 的租户字典与参数管理

**What to build:** 按 [spec](../spec.md) 第 3、6、7 节，在 iam 新功能包 `referencedata` 中提供租户对 Extensible Dictionary 与可修改 Parameter 的修改、成员可读的字典接口、变更事件与租户清理。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 新增权限码 `iam:reference-data:manage`（Tenant Admin 预置拥有）
- [ ] 自定义项：租户填写编码、系统加 `custom.` 前缀、「租户 + 字典」内唯一、不可修改；只能停用不能删除
- [ ] 内置项：停用或启用、排序、改显示名；编码不能改
- [ ] 可选项显示名在「租户 + 字典」内唯一（改名、新增、启用时检查）
- [ ] 固定字典只读；只有 `tenantEditable` 的参数可覆盖、值须满足约束，可恢复默认
- [ ] 合并后的字典接口对当前 Tenant 全部成员开放，含停用与下线项并带标记；参数只在管理接口返回
- [ ] Manifest 新版本删除的内置项按「不再可选」返回；删除的参数的覆盖值保留但不生效
- [ ] 修改后经 Outbox 发出 `ReferenceDataChanged`（带租户）
- [ ] 租户删除时逻辑删除该租户的自定义项与覆盖值
- [ ] 审计 `dictionary.itemCreate/itemUpdate/itemDisable/itemEnable`、`parameter.override/reset`
- [ ] 表带标准列与租户隔离（RLS），手写 SQL 只在 mapper XML
- [ ] 测试覆盖：前缀与唯一性、名称冲突、固定字典与不可改参数被拒、约束校验、事件发出、无权限 403

## Comments
