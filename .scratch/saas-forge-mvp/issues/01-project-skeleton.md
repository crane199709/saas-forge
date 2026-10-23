# 01: 工程骨架与版本组合验证

**What to build:** 开发者克隆仓库后，一条命令启动本地依赖（PostgreSQL、Redis、Nacos、RocketMQ、S3 兼容存储），再启动网关、iam、platform、demo-service 四个后端服务，全部注册到 Nacos，经网关能访问各服务并看到聚合后的 OpenAPI 文档；两个前端 Shell App（platform-console、tenant-app）能本地运行并构建。前后端文案从一开始就走 i18n key（第一期只有简体中文）。版本锁定见 ADR-0013，接口文档方案见 ADR-0010。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 后端为 Maven 多模块工程，含 BOM，版本为 JDK 17、Spring Boot 4.0.9、Spring Cloud 2025.1.4、Spring Cloud Alibaba 2025.1.0.0；groupId 为 `io.saasforge`
- [ ] SDK starter 模块按规格第 4 节建立空壳并能被 demo-service 引入
- [ ] docker compose 一键启动全部本地依赖，数据卷位于已忽略的本地数据目录
- [ ] 四个服务启动成功并在 Nacos 可见，配置通过 `spring.config.import` 从 Nacos 读取
- [ ] 网关能路由到各服务，并聚合展示各服务的 OpenAPI（springdoc 3.x + Swagger UI）
- [ ] 前端为 pnpm workspace，含两个 Shell App 与 `@forge/ui`、`@forge/sdk` 空包，均可运行与构建；Element Plus、Pinia、vue-i18n 已接入
- [ ] 后端 MessageSource 与前端 vue-i18n 均已配置，示例文案通过 key 渲染
- [ ] README 或等价文档写明本地启动步骤
- [ ] 若 Boot 4.0.9 + Cloud 2025.1.4 + SCA 2025.1.0.0 组合存在不兼容，记录现象与所采用的降级版本，并更新 ADR-0013
