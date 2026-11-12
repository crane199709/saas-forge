# 03: IAM 数据安全配置与 Shell 页面

**What to build:** 按 [spec](../spec.md) 的“实现决策”，IAM 新增数据安全子域：分级、规则、敏感字段登记、租户覆盖与预览；租户端与运营端提供管理页面。

**Blocked by:** 01, 02

**Status:** claimed

- [ ] 数据分级：平台定义 L1–L4 与默认规则；租户不可改分级
- [ ] 脱敏规则：平台维护固定策略规则；租户只能为字段选择或覆盖规则
- [ ] 敏感字段登记：读取 Manifest 声明，展示字段路径、分级、生效规则与状态
- [ ] 预览接口：对样例值返回脱敏结果；失败时返回明确错误
- [ ] 被字段引用的规则不能删除；保存与启动时校验引用
- [ ] 独立 Permission Code；配置变更写审计，审计详情不含原文
- [ ] 租户端“数据安全”页与运营端分级/规则管理页
- [ ] 测试覆盖：配置 CRUD、预览、删除保护、权限拒绝、跨租户隔离

## Comments


### 后端与运行时实现进展

- 已实现固定 L1–L4、九种预置规则、平台 CRUD/分级默认/预览、Manifest 敏感字段登记、租户选择与恢复、服务凭证限定的内部策略接口。
- 租户覆盖启用强制 RLS；平台持统一事务锁，逐租户独立只读事务检查当前覆盖引用。变更事务在首次鉴权/清单读取前拿锁，避免 MyBatis 一级缓存使用锁前旧清单。
- `PermissionService` 经本包 `ManifestPolicy` 接入规则引用校验与字段登记审计，功能包保持单向依赖。SDK HTTP 提供方无跨请求缓存，仅显式启用运行时时要求 IAM 地址与服务凭证；文档与脚手架说明已同步。
- 验证：`DataSecurityTest` 9 个真实 HTTP 测试通过，包含租户隔离、只读/拒绝、引用保护、覆盖和 Manifest 上报与删除的并发竞态、审计不含样例；`DataSecurityStartupTest` 2 个真实应用启动测试通过。`FieldMaskingRuntimeTest` 3 个客户端/自动配置测试通过。IAM ArchitectureTest 与 SchemaCommentTest 通过；ResourceManifestTest 26、RoleManagementTest 13、PlatformRoleTest 3 个回归通过。sub-app-kit 7 个生成/隔离测试通过。
- UI 与截图验收由并行辅助实现，合入后统一关闭工单。
