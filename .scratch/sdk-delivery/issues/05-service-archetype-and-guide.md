# 05: 后端工程模板（archetype）与接入指南

**What to build:** 新增 Maven archetype，参数为服务名、路径前缀、根包名，一条命令生成可编译、可启动、能通过自身测试的 Business Service。生成的工程以发布的 forge-dependencies 为父 POM，内容以 demo-service 为准；同时编写业务服务接入指南。见[规格 · 后端工程模板](../spec.md#后端工程模板maven-archetype)。

**Blocked by:** 02 网关按配置声明接入服务, 03 服务数据库准备脚本与平台侧接入清单, 04 统一版本与发布流程

**Status:** ready-for-human

- [x] archetype 模块加入构建与发布范围；生成的 POM 以 forge-dependencies 为父 POM，不引用 monorepo 内的聚合 POM
- [x] 默认引入 core、web、security、tenant、abac、mq、provisioning、audit、openapi、manifest 这几个 starter；storage 不默认引入
- [x] 生成一个符合编码规范包结构的示例功能包：声明权限码与 Data Resource，接口返回统一信封，含一个 `@AuditLog` 操作，SQL 写在 mapper XML，实体继承 `AuditableEntity`
- [x] 生成 TenantCreated 的幂等初始化订阅、Flyway 迁移（标准列、ENABLE + FORCE RLS、`WHERE NOT deleted` 部分唯一索引）、Resource Manifest 清单文件
- [x] 配置文件中环境相关项不设默认值，每项注释说明含义与提供方（平台运维 / 接入清单的哪一步）
- [x] 生成架构规则测试（引入 forge-architecture），以及 Testcontainers 的租户隔离、功能权限、数据权限测试样例
- [x] 生成镜像构建配置（与 07 的方式一致；07 未完成时先留空，07 完成后补上）
- [x] 新增业务服务接入指南：从生成工程到平台上看到菜单的完整顺序；必须配置项清单；常见启动失败及原因；Manifest version 何时升级；Provisioning 订阅与幂等；`/internal/` 约定；按需引入 storage；引用 03 的接入清单
- [x] archetype 自带集成测试：用默认参数生成工程，并对生成的工程运行 `verify`（编译、架构规则、Testcontainers 样例全部通过）
- [x] 生成的示例代码与 demo-service 的对应部分一致；demo-service 保留，作为平台端到端验收样本


## Comments

### 2026-10-30 实现与本地验收

- 新增 `forge-service-archetype` 并加入 backend reactor 与既有发布流程，继承公开 BOM；参数为 serviceName、pathPrefix、package（另有 Maven 通用坐标）。默认十个 SDK starter，不引入 storage。保留 demo-service 原样，模板复用其订单 CRUD、分类初始化、Outbox 通知、Mapper XML、标准列/RLS 迁移与权限测试。
- 生成工程有独立数据库准备夹具，无 monorepo 相对路径；默认参数生成 `com.example.sample` / `sample-service`，业务版本 `1.0.0-SNAPSHOT` 与 SDK `0.1.0` 不同。这个消费场景暴露 BOM 使用 project.version 的缺陷，已改为独立 forge.version，版本同步脚本同时维护它。
- 测试接缝按规格为生成工程的 verify：先添加默认参数集成案例，得到 reactor 缺少 archetype 模块的红灯；实现后生成工程 14 项测试全部通过（6 租户隔离、5 Data Policy、2 功能权限、1 共享架构规则），0 失败/错误/跳过，并完成 Boot 可执行 jar 打包。首次外层 verify 发现 POM 格式问题，修复后 clean verify 再次通过；日志 `/tmp/ticket05-green3.log`。
- archetype 测试通过独立 Maven 进程解析 SDK，无法看到外层尚未 install 的 reactor。普通 `verify` 保持冷缓存可运行；`install -Parchetype-it` 先逐模块安装本次制品，再启用模板生成测试。CI 与 README 使用此完整入口，已安装 SDK 后可单独运行 `-pl forge-service-archetype clean verify -Parchetype-it`。
- archetype 本地 `file:///tmp/forge-ticket05-repository` deploy 成功，主 jar、POM、sources.jar 均已发布，符合既有发布自检。版本脚本 0.1.0 → 0.2.1 → 0.1.0 往返无差异，标签校验通过。日志 `/tmp/ticket05-deploy.log`。
- 新增 `docs/business-service-guide.md`，涵盖生成、必须配置、启动排障、Manifest 升级、幂等 Provisioning、内部接口、storage 和从平台准备到租户菜单的完整步骤，引用 03 接入清单。模板环境地址/账号/密码均不设默认值。
- 镜像配置按本工单约定留给 07 补齐，尚未勾选；08 将验证仅消费发布制品的完整平台冒烟（实际 Manifest、网关、菜单/MQ 联调不由本次隔离 HTTP 测试证明）。未访问共享业务库、未发布远端制品、未部署服务。实现转 ready-for-human 供审阅。

- 07 补齐 Spring Boot build-image 配置与独立工程命令；实际后端镜像运行验收见 07，未将配置完成等同于容器健康验收。
