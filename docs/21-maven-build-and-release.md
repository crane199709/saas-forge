# Maven 构建与制品发布

> **状态**：本文是设计基线，描述长期有效的目标与约束，不代表对应功能已实现；当前实现状态见 [README 的当前状态](../README.md#当前状态) 与开放 Issues，进度勾选见 [MVP 开发计划](16-mvp-development-plan.md)。涉及前端界面的部分写作于自建 Design System / React Shell 时期，已由 [ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md) 替代；现行实现是 Vue 3 + Element Plus + Soybean Admin。

## 工具链基线

仓库唯一构建入口为 Maven Wrapper。Wrapper 固定 Maven 3.9.14，并通过 `distributionSha256Sum` 校验下载的发行包；本地与 CI 都使用 `./mvnw`。父 POM 的 Enforcer 只接受 Maven 3.9.14 以及 JDK 17。

所有 Java 源码统一以 `release=17` 编译。当前开发阶段仅支持 JDK 17 构建与运行；Pull Request 与普通 Push 必须在 JDK 17 上通过完整的：

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

## 父 POM 与版本管理

根 `pom.xml` 同时作为 Reactor 聚合器和全仓库构建父 POM，并继承 `spring-boot-starter-parent`：

- Spring Boot 管理其生态内的依赖和插件版本；
- 非 Spring Boot 第三方依赖由根 POM 的 `dependencyManagement` 和版本属性统一管理；
- 仓库内部依赖由根 POM 统一管理，子模块不声明内部依赖版本；
- `saas-forge-bom` 只向使用者导出首版 `sdk-core`、`sdk-auth`、`sdk-tenant` 与 Starter 的版本，不承担仓库构建管理；
- 子模块不得使用 `LATEST`、`RELEASE` 或版本区间；正式发布不得依赖任何 `SNAPSHOT`；
- Enforcer 在 `validate` 阶段检查工具链、重复依赖、动态版本、依赖收敛、Reactor 版本一致性和插件版本。

仓库使用 Maven CI-friendly `${revision}`。默认值为 `0.1.0-SNAPSHOT`；正式发布由标签提供不带 `SNAPSHOT` 的版本。Flatten Plugin 为安装和发布生成已解析 `${revision}` 的消费者 POM。

## 测试与覆盖率

- Surefire 在 `test` 阶段只运行 `*Test`；
- Failsafe 在 `integration-test` 与 `verify` 阶段运行 `*IT`；
- Testcontainers、数据库、Redis、Kafka 和跨模块契约验证使用 `*IT`；
- 浏览器端到端测试、性能测试和 ZAP 由独立 CI Job 负责，不并入 Maven 父 POM；
- JaCoCo 同时采集单元测试和集成测试覆盖率，由 `saas-forge-quality-gates` 生成 Reactor 聚合报告并执行门禁；
- 全仓行覆盖率不得低于 80%，分支覆盖率不得低于 70%；
- `iam-service`、`tenant-access-service`、`entitlement-service`、`saas-forge-sdk-auth`、`saas-forge-sdk-tenant`、`saas-forge-sdk-permission` 与 `saas-forge-sdk-quota` 的行覆盖率不得低于 90%；
- 生成代码和无业务逻辑的 `*Application` 启动入口不计入覆盖率；没有生产代码的空模块不阻断构建。

## Maven Central 发布

公开 Maven 坐标使用 `io.github.crane199709`；Java 包名继续使用 `io.saas.forge.*`。坐标绑定维护者当前 GitHub 账号名的规则见 [ADR 0053](adr/0053-maven-groupid-follows-github-account.md)，"使用可验证 GitHub 命名空间、不依赖品牌域名"的原始决策见 [ADR 0012](adr/0012-maven-coordinates-use-github-namespace.md)。

Maven Central 发布白名单为：

- 根父 POM `saas-forge`；
- Starter 运行所需但不由消费者直接声明的 `saas-forge-http-route-catalog`；
- `saas-forge-bom`；
- `saas-forge-sdk-core`、`saas-forge-sdk-auth` 与 `saas-forge-sdk-tenant`；
- `saas-forge-spring-boot-starter`。

Permission、Feature、Quota 与 Audit SDK 仍是未交付的 Reactor 占位模块，不进入 BOM、Starter 或发布白名单。Gateway、领域服务、`saas-forge-quality-gates`、测试支持、纯聚合模块及尚未确定打包契约的 OpenAPI、Protobuf、事件模块也不得部署到 Maven Central。每个 POM 必须显式声明 `maven.deploy.skip`，质量门会拒绝发布白名单漂移；仓库不发布远程 `SNAPSHOT`。

首版公开 SDK 与 Starter 通过 [`saas-forge-sdk/public-api-allowlist.json`](../saas-forge-sdk/public-api-allowlist.json) 固定允许的 package 和公共类型。Maven Enforcer 检查传递依赖，制品质量门检查公共签名、JAR 内容与 `jdeps` 实现引用，拒绝内部 Protobuf、gRPC、持久化与浏览器安全参数泄漏。首个正式 SDK 发布前没有真实二进制兼容基线；发布首版后才以已发布制品启用版本间比较。

受保护的 `vX.Y.Z` 标签触发 `.github/workflows/release.yml`。发布流程先在 JDK 17 上以 `X.Y.Z` 执行完整 `verify`，全部通过后才由 JDK 17 重新构建正式制品。Release Profile 附加 sources、Javadoc 和 GPG 签名，通过 Central Publisher Portal 自动公开并等待 `published` 结果。

GitHub Actions 需要配置以下 Secrets：

- `CENTRAL_USERNAME`
- `CENTRAL_PASSWORD`
- `MAVEN_GPG_PRIVATE_KEY`
- `MAVEN_GPG_PASSPHRASE`

发布构建以标签提交时间覆盖 `project.build.outputTimestamp`，并在 Job Summary 记录版本、提交 SHA、JDK、Maven 与 SDK/Starter JAR 的 SHA-256。正式发布只允许由 CI 执行，本地 `deploy` 不作为发布路径。

## 坐标改名与命名空间重注册

维护者 GitHub 账号改名会改变公开坐标，规则与冻结条件见 [ADR 0053](adr/0053-maven-groupid-follows-github-account.md)。账号改名后，新命名空间必须在 Central Publisher Portal 重新注册并通过归属校验，`CENTRAL_USERNAME` 与 `CENTRAL_PASSWORD` 也必须对应可发布该命名空间的账号；`MAVEN_GPG_PRIVATE_KEY` 与 `MAVEN_GPG_PASSPHRASE` 不受改名影响。旧命名空间保留但不再发布任何版本。

改名后的首次发布前必须核对：根 POM 与各模块 POM 的 `<groupId>`、发布白名单以及 `JavaSdkReleaseBoundaryIT`、`RepositoryStandardsTest` 中的坐标断言一致；全仓只剩 ADR 0012 与 ADR 0048 中标注为历史坐标 `io.github.crane0927` 的注释；Central 上不存在新命名空间的已发布制品或命名冲突。
