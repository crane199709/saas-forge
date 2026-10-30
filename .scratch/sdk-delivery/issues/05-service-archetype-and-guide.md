# 05: 后端工程模板（archetype）与接入指南

**What to build:** 新增 Maven archetype，参数为服务名、路径前缀、根包名，一条命令生成可编译、可启动、能通过自身测试的 Business Service。生成的工程以发布的 forge-dependencies 为父 POM，内容以 demo-service 为准；同时编写业务服务接入指南。见[规格 · 后端工程模板](../spec.md#后端工程模板maven-archetype)。

**Blocked by:** 02 网关按配置声明接入服务, 03 服务数据库准备脚本与平台侧接入清单, 04 统一版本与发布流程

**Status:** ready-for-agent

- [ ] archetype 模块加入构建与发布范围；生成的 POM 以 forge-dependencies 为父 POM，不引用 monorepo 内的聚合 POM
- [ ] 默认引入 core、web、security、tenant、abac、mq、provisioning、audit、openapi、manifest 这几个 starter；storage 不默认引入
- [ ] 生成一个符合编码规范包结构的示例功能包：声明权限码与 Data Resource，接口返回统一信封，含一个 `@AuditLog` 操作，SQL 写在 mapper XML，实体继承 `AuditableEntity`
- [ ] 生成 TenantCreated 的幂等初始化订阅、Flyway 迁移（标准列、ENABLE + FORCE RLS、`WHERE NOT deleted` 部分唯一索引）、Resource Manifest 清单文件
- [ ] 配置文件中环境相关项不设默认值，每项注释说明含义与提供方（平台运维 / 接入清单的哪一步）
- [ ] 生成架构规则测试（引入 forge-architecture），以及 Testcontainers 的租户隔离、功能权限、数据权限测试样例
- [ ] 生成镜像构建配置（与 07 的方式一致；07 未完成时先留空，07 完成后补上）
- [ ] 新增业务服务接入指南：从生成工程到平台上看到菜单的完整顺序；必须配置项清单；常见启动失败及原因；Manifest version 何时升级；Provisioning 订阅与幂等；`/internal/` 约定；按需引入 storage；引用 03 的接入清单
- [ ] archetype 自带集成测试：用默认参数生成工程，并对生成的工程运行 `verify`（编译、架构规则、Testcontainers 样例全部通过）
- [ ] 生成的示例代码与 demo-service 的对应部分一致；demo-service 保留，作为平台端到端验收样本
