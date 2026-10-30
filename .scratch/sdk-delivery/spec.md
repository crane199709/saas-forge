# SDK 可交付规格

Status: ready-for-agent

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准，关键决策见 [docs/adr/](../../docs/adr/)。本规格承接 [saas-forge-mvp](../saas-forge-mvp/spec.md) §1 的目标 2：「提供 Forge SDK，让内部团队快速搭建接入平台的 Business Service 与 Sub-App」。

## Problem Statement

第一期的平台核心与 Forge SDK 已经实现，但 SDK 只在 monorepo 里被 demo-service 和 demo-sub-app 用过。仓库外的内部团队现在想基于 SDK 做一个 Business Service，会依次遇到这些问题：

- **拿不到制品**：Maven 模块与 npm 包都没有发布配置，版本停在 `0.1.0-SNAPSHOT`，私有制品仓库也没选定。团队只能拷贝源码。
- **不知道从哪开始**：后端没有工程模板，也没有接入指南。团队只能对照 demo-service 反推：要引哪些 starter、配哪些必须项（漏配会启动失败）、怎么写 Resource Manifest、Flyway 双账号与 RLS 怎么配、怎么订阅 Provisioning 事件、怎么接入架构规则检查。
- **前端脚手架在仓库外不能用**：`@forge/sub-app-kit` 标为私有包；生成 Sub-App 的脚本把工程写进 monorepo，还从 tenant-app 读取共享依赖版本。
- **接入要改平台代码**：网关按服务前缀放行与拒绝的规则写死在 Java 代码里（只认 iam、demo、platform 三个前缀），新服务的 `/internal/` 接口也不会被网关拒绝。数据库 schema 与两个账号、iam 的 Manifest 上报令牌、RocketMQ Topic、网关路由，这些平台侧准备工作分散在本地 compose 初始化脚本和各服务配置里，没有文档。
- **部署不了**：没有任何服务镜像，连试点环境都搭不起来。
- **质量没有守门**：仓库没有配置远端，现有的 GitHub Actions 工作流从未运行过；它也只跑真实环境的主链路 E2E，`mvnw verify`（Testcontainers 集成测试、ArchUnit、Spotless）和前端的类型检查、单测、格式检查、mock E2E 都靠人在本地记得去跑。SDK 一旦发布给其他团队，回归问题会直接扩散出去。

平台维护者那边的问题是：每发布一个 SDK 版本，都无法确认它能在仓库外正常使用。

## Solution

把「SDK 能被仓库外团队真实接入」作为一个可验证的交付物：

1. 每个 PR 与 main 都经过完整的 CI 质量门。
2. 平台以统一版本号发布 Maven BOM、SDK starter、架构规则模块、后端工程模板（Maven archetype）和前端 npm 包，同时发布平台服务与 Shell App 的镜像。发布目标仓库可配置，选型确定之前，CI 发布到临时的本地仓库来验证流程。
3. 团队用 archetype 一条命令生成可直接启动的 Business Service，用发布后的 `@forge/sub-app-kit` 一条命令生成 Sub-App。生成的工程默认接好租户隔离、权限、Manifest、统一信封、架构规则检查和测试样例。
4. 平台侧的接入步骤改为配置驱动并写成清单：网关通过配置声明服务前缀，数据库账号、Manifest 令牌、Topic 有可重复执行的准备方式，接入新服务不用改平台代码。
5. CI 有一个「模板冒烟」任务：在仓库外的空目录里，只从发布出来的制品生成服务和 Sub-App，并完成构建、测试与启动。每个版本发布前都证明 SDK 在仓库外可用。
6. 最后由一个真实的内部团队在仓库外完成试点接入，问题反馈回 SDK。

## User Stories

### 业务服务开发者（后端）

1. As a 业务服务开发者, I want to 从私有 Maven 仓库按版本引入 Forge BOM, so that 我不需要拷贝平台源码，也不用自己对齐 Spring Boot、Spring Cloud 与 SCA 的版本组合
2. As a 业务服务开发者, I want to 只引入我需要的 SDK starter, so that 不用对象存储或审计的服务不会被迫带上这些依赖与配置
3. As a 业务服务开发者, I want to 用一条 archetype 命令生成新的 Business Service, so that 几分钟内就能得到一个能编译、能启动、能通过测试的工程
4. As a 业务服务开发者, I want to 在生成时指定服务名、路径前缀和根包名, so that 生成的工程符合平台的命名与包结构约定
5. As a 业务服务开发者, I want 生成的工程自带一个最小的业务示例（带权限码、数据权限和审计的增删改查）, so that 我能照着示例写自己的第一个接口
6. As a 业务服务开发者, I want 生成的工程默认接入架构规则检查, so that 违反持久层、包结构和信封规范的代码在我本地构建时就失败
7. As a 业务服务开发者, I want 生成的工程包含用 Testcontainers 跑的租户隔离与权限测试样例, so that 我知道该怎么测试自己的业务
8. As a 业务服务开发者, I want 生成的配置文件里每个必须项都有注释，说明含义和由谁提供, so that 启动失败时能马上知道缺了什么、找谁要
9. As a 业务服务开发者, I want 一份接入指南按顺序讲清从生成工程到在平台上看到菜单的全过程, so that 我不需要读 demo-service 源码来反推做法
10. As a 业务服务开发者, I want 指南列出常见启动失败及其原因, so that 漏配签发者地址、Manifest 令牌或数据库账号时能自己排查
11. As a 业务服务开发者, I want 指南说明 Resource Manifest 的 version 什么时候要升级, so that 权限码、菜单或 Data Resource 改动后能被 iam 正确接收
12. As a 业务服务开发者, I want 指南说明怎么订阅 TenantCreated 并幂等初始化本服务的数据, so that 新租户开通时我的服务也完成初始化
13. As a 业务服务开发者, I want 指南说明内部接口要放在 `/internal/` 下, so that 我知道哪些接口会被网关拒绝、哪些对外暴露
14. As a 业务服务开发者, I want 用一条命令为服务构建容器镜像, so that 能把服务部署到试点环境
15. As a 业务服务开发者, I want 在本地用平台提供的 compose 启动全部依赖与平台服务, so that 不依赖共享环境也能端到端调试自己的服务
16. As a 业务服务开发者, I want SDK 的每个版本都有变更说明，并标出不兼容变更, so that 升级前能评估影响

### Sub-App 开发者（前端）

17. As a Sub-App 开发者, I want to 从私有 npm 仓库安装 `@forge/sdk`、`@forge/ui` 与 `@forge/sub-app-kit`, so that 我在自己的仓库里就能开发 Sub-App
18. As a Sub-App 开发者, I want to 在任意空目录用一条命令生成 Sub-App 工程, so that 不需要克隆平台 monorepo
19. As a Sub-App 开发者, I want 生成的工程的共享依赖版本与平台当前发布的 Shell 一致, so that 运行时共享单例不会因为版本不同而出问题
20. As a Sub-App 开发者, I want 生成的工程自带独立开发宿主和隔离约定 lint, so that 不启动 Shell 也能开发，并且违反隔离约定时 lint 失败
21. As a Sub-App 开发者, I want 生成的工程带一个用 `ListPage` 的列表页示例, so that 我知道怎么对接分页接口和平台的界面风格
22. As a Sub-App 开发者, I want 指南说明怎样在 App Registry 登记入口地址并在套餐中开放, so that 我的 Sub-App 能被租户看到

### 平台维护者

23. As a 平台维护者, I want 每个 PR 都运行后端 `verify`（含集成测试、架构规则与格式检查）, so that 回归在合并前就被发现
24. As a 平台维护者, I want 每个 PR 都运行前端类型检查、单测、格式检查、构建与 mock E2E, so that 前端回归也在合并前被发现
25. As a 平台维护者, I want 真实环境的主链路 E2E 继续在 PR 与 main 上运行, so that 跨服务的主流程始终可用
26. As a 平台维护者, I want CI 的模板冒烟任务在仓库外的空目录里只用发布出来的制品生成并构建服务和 Sub-App, so that 每次改动都证明 SDK 在仓库外可用
27. As a 平台维护者, I want 推送版本标签时自动发布全部 Maven 制品、npm 包和镜像, so that 发布可重复，不依赖某个人的本机环境
28. As a 平台维护者, I want 后端与前端制品使用同一个版本号, so that 团队按一个版本就能拿到相互兼容的全部组件
29. As a 平台维护者, I want 发布目标仓库地址与凭证都来自配置和 CI 密钥, so that 仓库选型确定后只改配置，不改构建脚本
30. As a 平台维护者, I want 发布前校验版本号不是 SNAPSHOT 且与标签一致, so that 不会误发布一个不可追溯的版本
31. As a 平台维护者, I want 发布的 Maven 制品带源码包, so that 业务开发者在 IDE 里能看到 SDK 的源码和文档注释
32. As a 平台维护者, I want 只发布 SDK、BOM、架构规则、archetype 与前端包，不发布平台服务本身的 jar, so that 外部团队不会把平台内部实现当作依赖
33. As a 平台维护者, I want 平台服务（gateway、iam、platform）与两个 Shell App 都能构建出镜像, so that 能搭建试点环境

### 平台运维（接入新服务）

34. As a 平台运维, I want 在网关配置中声明新服务的路径前缀, so that 接入新服务不需要修改和重新发布网关代码
35. As a 平台运维, I want 网关对每个已声明的服务前缀默认要求认证、拒绝 `/internal/`、放行 API 文档, so that 新服务自动遵守网关的安全约定
36. As a 平台运维, I want 未声明前缀的请求一律被网关拒绝, so that 漏配的服务不会意外暴露
37. As a 平台运维, I want 用一个可重复执行的脚本为新服务创建 schema、迁移账号与运行账号, so that 各环境的数据库准备方式一致，且账号不是超级用户、没有 BYPASSRLS
38. As a 平台运维, I want 用配置为新服务登记 Manifest 上报令牌, so that 只有登记过的服务能向 iam 上报清单
39. As a 平台运维, I want 一份接入清单列出新服务上线前平台侧要做的全部事项, so that 接入时不会遗漏数据库、令牌、Topic、路由、API 文档聚合或 App Registry 中的任何一项
40. As a 平台运维, I want 网关聚合的 OpenAPI 文档自动包含已声明的服务, so that 新服务的接口文档无需额外配置就能在 Swagger UI 中看到

### 试点团队

41. As a 试点团队, I want 按指南在自己的仓库里从零完成一个真实服务的接入, so that 验证 SDK 真的能被仓库外团队使用
42. As a 试点团队, I want 把接入中遇到的问题记录为平台工单, so that SDK 和指南能据此改进
43. As a 平台维护者, I want 试点结束后有一份记录：耗时、卡点、指南缺漏, so that 决定下一步优先改什么

## Implementation Decisions

### 统一版本与发布

- 后端全部可发布模块与前端 npm 包使用同一个版本号，遵守语义化版本，1.0 之前从 0.x 开始，次版本号变化可以包含不兼容变更，但必须写进变更说明。
- 发布由推送版本标签触发。发布流程先校验标签与各处版本号一致且不是 SNAPSHOT，然后跑完整质量门，最后发布 Maven 制品、npm 包与镜像。
- Maven 发布范围：BOM（forge-dependencies）、全部 forge-sdk-* 模块、forge-architecture、新的 archetype 模块。平台服务（gateway、iam、platform）与 demo-service 不发布 jar。发布的模块带源码包。
- npm 发布范围：`@forge/sdk`、`@forge/ui`、`@forge/sub-app-kit`（取消 private，补齐发布所需字段，可执行文件发布为编译后的 JavaScript，不依赖 Node 的 TypeScript 类型剥离）。
- 制品仓库地址、npm registry、镜像仓库地址与凭证全部由构建参数与 CI 密钥提供，构建配置中不写死任何地址。私有仓库选型确定之前，CI 发布到任务内临时启动的本地 Maven 仓库与本地 npm registry，以此验证发布流程；镜像只构建，不推送。
- 每个版本在仓库根目录的变更日志中记录变更，单列不兼容变更。

### 后端工程模板（Maven archetype）

- 新增一个 archetype 模块，参数为：服务名、路径前缀、根包名。
- 生成的工程以发布的 forge-dependencies 为父 POM，不依赖 monorepo 内的聚合 POM。
- 默认引入 core、web、security、tenant、abac、mq、provisioning、audit、openapi、manifest 这几个 starter。storage 不默认引入，指南说明如何按需添加。
- 生成内容：
  - 符合编码规范包结构的示例功能包：controller、service、mapper、entity，以及 XML 中的手写 SQL。实体继承 AuditableEntity。
  - 示例接口声明权限码与 Data Resource，返回统一信封，带一个 @AuditLog 操作。
  - TenantCreated 的幂等初始化订阅。
  - Flyway 迁移：标准列、RLS（ENABLE + FORCE）、部分唯一索引。
  - Resource Manifest 清单文件。
  - 配置文件：环境相关项不设默认值，每项写注释说明由谁提供。
  - 架构规则测试，以及 Testcontainers 的租户隔离与权限测试样例。
  - 构建镜像的配置。
- 模板内容以 demo-service 为准；demo-service 仍保留，作为平台自身端到端验收的固定样本。

### 前端脚手架

- `@forge/sub-app-kit` 的生成命令改为可在任意目录运行：生成到当前目录下的指定位置，共享依赖版本取自 sub-app-kit 发布时记录的、与该版本 Shell 一致的版本表，不再读取 monorepo 中的 tenant-app。
- 生成的工程依赖已发布的 `@forge/*` 包版本，不使用 workspace 协议。
- 生成的工程带一个使用 `ListPage` 的列表页示例。monorepo 内原有的生成方式继续可用。

### 网关配置驱动

- 网关新增配置项，声明已接入的服务列表，每项包含路径前缀与所属 Realm（Tenant Realm 或 Platform Realm）。配置用 `@ConfigurationProperties` 的 record 绑定，没有默认值；漏配时启动失败。
- 网关的安全规则按配置生成：已声明前缀下的 `/internal/**` 一律拒绝；`/v3/api-docs/**` 放行；其余要求认证，Token 的 issuer 必须与该服务所属的 Realm 一致。未声明的前缀一律拒绝。
- iam 自身的规则（OAuth2 端点、JWKS、匿名授权端点、iam 内部端点）保持在代码中，不纳入通用配置。
- OpenAPI 聚合的分组列表由同一份服务配置生成。
- 路由仍走 Nacos 配置中的 Spring Cloud Gateway 路由，指南给出新服务的路由模板。
- 这项改动修正了现有问题：demo 与 platform 的 `/internal/` 路径目前没有被网关拒绝，与编码规范不一致。

### 平台侧接入准备

- 数据库：把本地 compose 初始化中「为服务创建 schema、迁移账号、运行账号」的逻辑整理成可在任意环境重复执行的脚本，参数为服务名与两个账号的密码；脚本保证账号不是超级用户、没有 BYPASSRLS，运行账号不是表所有者。本地 compose 改为调用同一脚本。
- Manifest 令牌、RocketMQ Topic、网关路由与服务前缀、App Registry 登记，统一写入接入清单；Topic 的创建方式与本地脚本保持一致。

### 镜像

- 后端服务镜像用 Spring Boot 插件的镜像构建能力生成，不手写 Dockerfile；archetype 生成的工程使用相同方式。
- 两个 Shell App 的镜像是静态文件服务，提供 history fallback；运行时配置（如 API 地址）在容器启动时注入，不编译进产物。
- 本规格只负责产出镜像，不涉及编排（K8s 等）。

### CI 质量门

- 前提：仓库配置远端（GitHub）。这是人工步骤。
- PR 与 main 运行：
  - 后端 verify：需要 Docker，用于 Testcontainers。
  - 前端：类型检查、单测、格式检查、构建、mock E2E。
  - 现有主链路 E2E。
  - 模板冒烟。
- 各任务互相独立，可以并行，失败时保留报告。
- 发布工作流见「统一版本与发布」。

## Testing Decisions

好的测试只验证外部可观察的行为：生成的工程能否构建、启动并通过自身测试；网关对某个路径是放行、拒绝还是要求认证。不验证模板文件的具体内容，也不验证网关内部的规则构造方式。

本规格的测试只用两个接缝：

1. **模板冒烟（新增，最高层接缝）**：CI 任务在仓库外的空目录中：
   - 把当前提交的全部制品发布到临时本地 Maven 仓库与本地 npm registry。
   - 用 archetype 生成一个服务，运行它的 verify：编译、架构规则、Testcontainers 测试样例全部通过。
   - 启动生成的服务，确认它向 iam 上报 Manifest 成功：用主链路 E2E 的环境脚本拉起平台服务，网关配置中声明新前缀，经网关调用示例接口，按权限得到 200 或 403。
   - 用发布出来的 `@forge/sub-app-kit` 生成 Sub-App，跑通它的 lint、类型检查与构建。

   这一个接缝同时覆盖：发布流程、BOM 完整性、archetype、前端脚手架、网关配置驱动和接入清单的可执行部分。现有的参照：主链路 E2E 的一键环境脚本，以及 sub-app-kit 已有的生成器测试。

2. **网关安全测试（沿用现有接缝）**：在现有网关安全测试中，把写死的 demo 前缀改为通过配置声明。覆盖：已声明前缀的认证要求与 Realm 不匹配被拒、`/internal/` 被拒、API 文档放行、未声明前缀被拒、漏配时启动失败。

其余改动不新增测试：数据库准备脚本由本地 compose、主链路 E2E 和模板冒烟间接执行；镜像构建由发布工作流在 CI 中实际执行；Shell 镜像的 history fallback 由主链路 E2E 改为使用镜像后间接验证，如果改动成本过高，可以留到试点环境再验证，需在工单中注明。

## Out of Scope

- 私有制品仓库、镜像仓库的选型与搭建（见 Further Notes）。
- 部署编排（K8s、Helm）、生产环境形态、可观测采集后端。
- SDK 对外开源相关的工作（许可证、公开文档站点）。
- 新的 SDK 能力。试点暴露的需求另立工单。
- 混合版本兼容：Sub-App 与 Shell 使用不同共享依赖版本的验证。
- 一期「不在范围」中的各项（计费、MFA、SSO 等）。
- 前端 Shell 的进一步调整（等试点反馈）。

## Further Notes

- **待定项**：
  - 私有 Maven 仓库与 npm registry 用什么（Nexus、Verdaccio 或公司现有仓库）。
  - 镜像仓库用什么。
  - 生产数据库能否提供 PostgreSQL 17。

  这三项不阻塞本规格的实现（CI 用临时本地仓库验证），但会阻塞真实发布和试点。
- **人工步骤**：
  - 配置仓库远端与 CI 密钥。
  - 选定制品仓库。
  - 选定试点团队与试点服务。试点本身以 `ready-for-human` 工单跟踪。
- **建议拆分顺序**：
  1. CI 质量门。
  2. 网关配置驱动。
  3. 数据库准备脚本与接入清单。
  4. 统一版本与发布流程。
  5. 后端 archetype 与接入指南。
  6. 前端脚手架可在仓库外运行。
  7. 镜像。
  8. 模板冒烟任务。
  9. 试点（人工）。

  1、2、3 互不依赖；8 依赖 4、5、6。
- **顺带的整理**：saas-forge-mvp、unified-api-envelope、standard-table-columns、frontend-shell 中已完成工单的 `Status` 仍为 `ready-for-agent`，建议统一更新为表示已完成的状态，避免外部团队误判平台的完成度。
