# 03: IAM 数据安全配置与 Shell 页面

**What to build:** 按 [spec](../spec.md) 的“实现决策”，IAM 新增数据安全子域：分级、规则、敏感字段登记、租户覆盖与预览；租户端与运营端提供管理页面。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 数据分级：平台定义 L1–L4 与默认规则；租户不可改分级
- [x] 脱敏规则：平台维护固定策略规则；租户只能为字段选择或覆盖规则
- [x] 敏感字段登记：读取 Manifest 声明，展示字段路径、分级、生效规则与状态
- [x] 预览接口：对样例值返回脱敏结果；失败时返回明确错误
- [x] 被字段引用的规则不能删除；保存与启动时校验引用
- [x] 独立 Permission Code；配置变更写审计，审计详情不含原文
- [x] 租户端“数据安全”页与运营端分级/规则管理页
- [x] 测试覆盖：配置 CRUD、预览、删除保护、权限拒绝、跨租户隔离

## Comments


### 后端与运行时实现进展

- 已实现固定 L1–L4、九种预置规则、平台 CRUD/分级默认/预览、Manifest 敏感字段登记、租户选择与恢复、服务凭证限定的内部策略接口。
- 租户覆盖启用强制 RLS；平台持统一事务锁，逐租户独立只读事务检查当前覆盖引用。变更事务在首次鉴权/清单读取前拿锁，避免 MyBatis 一级缓存使用锁前旧清单。
- `PermissionService` 经本包 `ManifestPolicy` 接入规则引用校验与字段登记审计，功能包保持单向依赖。SDK HTTP 提供方无跨请求缓存，仅显式启用运行时时要求 IAM 地址与服务凭证；文档与脚手架说明已同步。
- 验证：`DataSecurityTest` 9 个真实 HTTP 测试通过，包含租户隔离、只读/拒绝、引用保护、覆盖和 Manifest 上报与删除的并发竞态、审计不含样例；`DataSecurityStartupTest` 2 个真实应用启动测试通过。`FieldMaskingRuntimeTest` 3 个客户端/自动配置测试通过。IAM ArchitectureTest 与 SchemaCommentTest 通过；ResourceManifestTest 26、RoleManagementTest 13、PlatformRoleTest 3 个回归通过。sub-app-kit 7 个生成/隔离测试通过。
- UI 与截图验收由并行辅助实现，合入后统一关闭工单。


## Answer

已完成后端与两端 Shell 页面并整合：后端提交 `f11de62`，UI 提交 `8071812`，整合提交 `03e6975`。租户与运营端均新增 `/data-security`，前后端请求、权限码和响应字段逐项核对一致。

### 实现结果

- 平台固定 L1–L4 与九种预置固定策略，支持规则新增、修改、逻辑删除、分级默认设置和共用引擎预览；L4 固定禁止导出。租户只能选择规则或恢复默认，不能编写表达式、正则或变更分级。
- 字段登记来自当前 Manifest，按租户覆盖 > 字段默认 > 分级默认解析，展示分级、有效规则、状态与导出限制。内部策略接口沿用 ServiceTokenFilter，由凭证 principal 限定服务身份；SDK 提供无缓存的 HTTP FieldMaskingPolicyProvider。
- 引用保护覆盖当前 Manifest、分级默认、所有未删除租户的当前字段覆盖；统一事务锁早于鉴权与清单读取，避免 MyBatis 锁前缓存竞态。覆盖表带标准列、中文注释、逻辑删除与 ENABLE/FORCE RLS；平台逐租户绑定上下文并用独立连接检查，不绕过 RLS。
- Manifest 上报、规则保存和 IAM 启动校验配置；已下线字段的历史覆盖不影响删除与重启。permission 内定义 ManifestPolicy 接缝，功能包依赖保持单向，架构规则通过。
- 独立的 read/manage 权限覆盖租户与运营端；IAM 清单版本分别升级至 8/6。规则、分级默认、字段登记、租户覆盖变更写审计，仅记录配置标识，预览样例不入审计。预览请求 toString 隐去原文，客户端异常不携带 IAM 响应体或嵌套 cause。
- 仅显式启用 `forge.field-masking.enabled=true` 时要求 `iam-uri` 和 `token`，地址与凭证无环境默认值，旧服务不新增必配项。已同步 standards、archetype 配置说明、sub-app-kit 生成说明、业务服务指南及 CHANGELOG，保持未发布 SDK `0.2.0-SNAPSHOT`。

### 验证证据

- 后端统一检查：`cd backend && ./mvnw -o -pl forge-iam -am test -Dtest=DataSecurityTest,DataSecurityStartupTest,FieldMaskingRuntimeTest,ArchitectureTest,SchemaCommentTest -Dsurefire.failIfNoSpecifiedTests=false`，BUILD SUCCESS。DataSecurityTest 9 个真实 HTTP 用例、StartupTest 2 个真实应用启动用例、RuntimeTest 3 个客户端/启动配置用例，以及 IAM/上游架构和表注释门禁均通过。日志 `/private/tmp/field-masking-03-final-check.log`。
- HTTP 覆盖配置 CRUD、规则与分级优先级、失败预览不回显、当前服务过滤、权限/Realm 拒绝、两租户隔离、租户 B 引用删除保护、只读授权、审计，以及覆盖/Manifest 上报与删除的两类并发竞态。启动测试验证损坏默认引用拒绝就绪、下线字段历史覆盖允许启动。
- 受影响既有回归：ResourceManifestTest 26、RoleManagementTest 13、PlatformRoleTest 3 个用例均通过。后端 Spotless 与 git diff --check 通过。sub-app-kit 7 个生成/隔离用例通过。
- 前端组件全量：tenant-app 235、platform-console 100，共 335 个用例通过；两端 typecheck、tools typecheck、lint:style、format:check 通过。
- Playwright `pnpm test:visual --grep=data-security` 30/30 通过，1440×900 与 1024×768 覆盖默认、编辑、预览、保存失败、加载失败、空态、分级编辑和删除引用失败。30 张 PNG 均已逐张查看，1024 租户表格额外验证横向滚动后规则、状态和导出列可达。UI 提供实现者记录 `/private/tmp/field-masking-03-ui-notes.md`。

### 验证边界

UI 浏览器测试使用 HTTP 路由替身；配置、安全边界和审计通过真实 IAM HTTP + PostgreSQL/Redis 验证，策略客户端通过真实 HTTP 服务边界替身验证。本工单没有运行连接真实 Shell、网关和业务服务的完整部署联调；导出、日志和在线响应适配分别由工单 04、05 完成，全模块回归由集成分支统一执行。
