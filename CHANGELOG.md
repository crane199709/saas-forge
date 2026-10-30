# 变更日志

后端 Maven 制品与前端 npm 包使用同一语义版本。1.0 之前的次版本可包含不兼容变更，升级前请检查本节。

## 0.1.0

### 新增

- 首次交付 Forge BOM、按能力拆分的 SDK starter 与共享架构规则，Java 模块附带源码包。
- 发布 `@forge/sdk`、`@forge/ui`、`@forge/sub-app-kit`；提供类型声明与编译后的 CLI。
- 提供统一版本命令、标签发布质量门和临时 Maven/npm 仓库消费自检。
- 提供 Business Service archetype、接入指南和可在仓库外运行的 Sub-App 生成器；BOM 的 SDK 版本独立于业务工程版本。
- 提供四个后端服务与两个 Shell 的镜像构建入口，以及从临时发布到仓库外生成、启动和权限校验的模板冒烟质量门。

### 不兼容变更

- Maven 版本从 `0.1.0-SNAPSHOT` 切换至 `0.1.0`，使用旧坐标的工程需更新 BOM 版本。
- 本版尚未承诺与其他版本的 Shell/Sub-App 共享依赖兼容；两端应使用同一版本。
