# 01: 工程骨架与版本组合验证

**What to build:** 开发者克隆仓库后，一条命令启动本地依赖（PostgreSQL、Redis、Nacos、RocketMQ、S3 兼容存储），再启动网关、iam、platform、demo-service 四个后端服务，全部注册到 Nacos，经网关能访问各服务并看到聚合后的 OpenAPI 文档；两个前端 Shell App（platform-console、tenant-app）能本地运行并构建。前后端文案从一开始就走 i18n key（第一期只有简体中文）。版本锁定见 ADR-0013，接口文档方案见 ADR-0010。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 后端为 Maven 多模块工程，含 BOM，版本为 JDK 17、Spring Boot 4.0.9、Spring Cloud 2025.1.4、Spring Cloud Alibaba 2025.1.0.0；groupId 为 `io.saasforge`
- [x] SDK starter 模块按规格第 4 节建立空壳并能被 demo-service 引入
- [x] docker compose 一键启动全部本地依赖，数据卷位于已忽略的本地数据目录
- [x] 每个服务在同一 PostgreSQL 实例中使用独立 schema；docker compose 初始化时为每个服务创建 schema 与两个账号：迁移账号（schema 所有者，供 Flyway 使用）与运行账号（仅数据读写权限、非表所有者，供应用连接）
- [x] Flyway 统一接入：BOM 锁定 Flyway 与其 PostgreSQL 模块版本（Boot 4 需单独的 Flyway starter，且需显式引入 PostgreSQL 数据库模块）；各服务启动时以迁移账号执行本服务迁移，应用数据源使用运行账号
- [x] 迁移脚本命名为 `V<版本>__<描述>.sql`，已执行的脚本不可修改；每个服务至少有一个初始迁移证明链路可用
- [x] 四个服务启动成功并在 Nacos 可见，配置通过 `spring.config.import` 从 Nacos 读取
- [x] 网关能路由到各服务，并聚合展示各服务的 OpenAPI（springdoc 3.x + Swagger UI）
- [x] 前端为 pnpm workspace，含两个 Shell App 与 `@forge/ui`、`@forge/sdk` 空包，均可运行与构建；Element Plus、Pinia、vue-i18n 已接入
- [x] 后端 MessageSource 与前端 vue-i18n 均已配置，示例文案通过 key 渲染
- [x] 后端接入 Spotless Maven 插件统一格式化：Java 使用 palantir-java-format，并移除未使用的 import；POM 统一排序与缩进；配置在父 POM 中统一声明，所有模块继承
- [x] `spotless:check` 绑定到构建的 verify 阶段，格式不合规时构建失败；`spotless:apply` 可一键修复
- [x] 前端使用 Prettier（版本锁定，配置位于前端工作区根目录）格式化 `.ts`、`.vue`、`.css`、`.json` 等文件，提供 `format` 与 `format:check` 脚本；前端格式化不依赖 Java 工具链
- [x] README 或等价文档写明本地启动步骤与前后端格式化命令
- [x] 若 Boot 4.0.9 + Cloud 2025.1.4 + SCA 2025.1.0.0 组合存在不兼容，记录现象与所采用的降级版本，并更新 ADR-0013

## Comments

2026-10-24 实现完成：
- 版本组合可用，无需降级，验证结果已记入 ADR-0013。
- 四个服务均已启动并注册到 Nacos 3.1.1，网关已能路由并聚合 OpenAPI。
- 后端 `./mvnw verify` 通过（含 Testcontainers 数据库账号测试与 spotless:check）。
- 前端 build/typecheck/test/format:check 通过。
- 本地 S3 选用 silo（规格第 16 节待定项）。

最后一条（不兼容时降级）因组合兼容而不适用。
