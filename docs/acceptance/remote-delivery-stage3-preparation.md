# 阶段 3 Remote Delivery 后端准备记录

状态：后端实现与专项诊断通过；Client 0.5.0 已发布并由前端精确安装；业务 Remote、同轮 Fresh Chrome 与日志/Trace/Audit 产品链路尚未验收，阶段 3 保持未完成。

2026-10-02 在 JDK 17 下执行以下验证。测试使用隔离 PostgreSQL 18；不修改已有数据库或共享环境。

| 检查 | 结果与范围 |
| --- | --- |
| Remote Delivery 生命周期、HTTP 契约、权限、租户资格 | 9 项通过；包含真实数据库和 Spring 事务代理，HTTP 控制器契约及权威适配器使用测试替身 |
| IAM OAuth Client 类型、Reserved 引导、数据库 Scope 约束 | 16 项通过；包含真实 PostgreSQL 的 V25 迁移及非法授权组合拒绝 |
| Route Catalog、Example 契约 | 3 项通过 |
| 五项仓库门禁 | 通过：Service/Scope 登记、公开接口归属、路由与认证契约匹配、禁止手写公开路由、服务实现依赖隔离 |
| Nacos 配置 | 28 份应用专属环境资源校验通过；未向实际 Nacos 发布 |
| Client 构建与诊断 | 0.5.0 ESM/类型编译通过；`verify-stage3.mjs` 验证正式请求路径、If-Match、幂等键、分页 null、命名空间兼容 |

复现命令分别选取 `ManifestLifecycleIT`、`ManifestHttpContractTest`、`ManifestAuthorityTest`、`GrpcTenantContextCheckerTest`，以及 IAM 的 `OAuthClientDomainTest`、`ReservedServiceClientBootstrapServiceTest`、`ReservedServiceClientBootstrapRunnerTest` 和 `IamPersistenceRepositoryIT#persistsCiAndRemoteDeliveryTypesWithExactDatabaseScopeBoundaries`。Client 在维护目录执行 `npm run build`、`node scripts/verify-stage3.mjs`。

未执行：完整后端 CI、实际 Nacos/Reserved 身份初始化、CI 注册与管理员浏览器审核/启用、正式租户业务 Remote、最终 Fresh Chrome 验收。此记录只支持后端准备结论，不支持开发计划中的浏览器验收勾选。最终要求见 [阶段 3 契约](../remote-delivery-stage3-contract.md)。

## Example 与隔离部署准备补充（2026-10-02）

- Example 复用已批准的根 POM Nacos Config Starter，新增各环境独立 `project-service.yaml`，安全配置固定 `refreshEnabled=false`；集成夹具显式关闭 Nacos Config。
- Example 的 `ProjectHttpIT` 执行 42 项：41 通过，1 项可选 Gateway/Kafka/Collector 诊断未启用而跳过；`ProjectContractTest` 通过。此处不是浏览器结果。
- Compose 布局检查覆盖 8 个独立应用、5 个验收组合；新增 Project 和 Remote Delivery 的迁移门禁、受限身份及阶段 3 组合。完整环境只供专项验收使用。
- handoff 与 HTTPS Remote 静态交付共 9 项通过；新增受控业务入口路径保持精确 Console CORS、只读、无凭据及真实 404。Fresh handoff 可检查全部 7 个应用，并关联独立 Edge 的随机回环端口。
- Manifest HTTP 检查实际创建 Spring 方法校验代理，确认非法分页返回规范 400；相关基础设施工具测试 22 项通过。
- 五项相关仓库门禁再次通过。新的部署清单尚待本轮实际环境启动验证，不代表最终交付。
