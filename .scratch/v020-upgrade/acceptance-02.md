# 工单 02：已有 Business Service 升级与数据保留

日期：2026-11-17。工单：[02](issues/02-business-service-upgrade.md)。状态：本票验收通过，不代表可选能力、试点或发布就绪全部通过。

## 基线与候选身份

- 固定起点：集成 `326ff1b625a849eda427ccfc6e96c79cd2d1ec4c`，工单分支 `codex/v020-upgrade-02`。
- 旧版输入：实际 Maven 发布仓库 `https://maven.pkg.github.com/crane199709/saas-forge` 的 `0.1.0`。只读下载 29 个 Forge POM/JAR，来源、大小与 SHA-256 在 `/private/tmp/v020-upgrade/02-green/published/provenance.json`；没有重建源码冒充发布制品。
- 旧 archetype JAR SHA-256：`900a50d8e5c8d94e3e6c611174fba6cc266acce17aa84da9395bfaf7c0fa4ef9`。已有缓存仅在来源 URL 和 SHA-256 均相符时复用；其余直接下载，重定向后不携带 Authorization，凭证不落盘。
- 候选：`/private/tmp/v020-upgrade/candidate-54115992`，制品源码提交 `54115992e00691be57b912e46593709c77c344a1`，坐标版本 `0.2.0-SNAPSHOT`。候选清单 SHA-256：`999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`。
- 两个工程均从实际 archetype 生成：业务坐标 `com.example:retained-service:7.4.0-SNAPSHOT`，包 `com.example.retained`，注册名 `retained-service`，前缀 `retained`。业务版本保持 `7.4.0-SNAPSHOT`，Manifest 配置版本保持 `11`，旧菜单路径 `overview` 保持不变。
- 旧工程先加入代表性业务控制器改动、`@ConfigurationProperties` 自定义配置 `business.invoice-prefix=KEEP-2026`、`.env.example` 及 POM 自定义属性，然后用真实 `0.1.0` SDK 构建旧服务 JAR。
- Maven 使用独立缓存；仅复制第三方缓存，排除本机全部 `io/saasforge` 与 `.lastUpdated`，从明确的旧发布 file 仓库和候选 file 仓库解析 Forge 依赖。

## 交付与实际差异

入口：`deploy/upgrade-smoke/business-service.sh`；实现与可复用发布输入下载器：`service_upgrade.py`、`published_maven.py`；跨旧/新服务 HTTP 用例：`fixtures/service/UpgradeAcceptanceTest.java`。所有生成工程、JAR、日志及大证据均在仓库外。

集中清单：[Business Service 0.2.0 升级](../../docs/upgrades/business-service-0.2.0.md)。同步接入指南入口和 CHANGELOG。本票不修改任何候选制品源码，不新增编码规则或默认可选能力，原候选身份保持不变。

对照实际生成工程后合入：父 BOM/SDK、直接 Ops/Validation 依赖、Spotless 与配置文件、forkCount、Wrapper、Ops 必配令牌、请求校验、SQL Mapper 当前写法、测试骨架和规范。实际旧包没有 Wrapper、AGENTS 或规范副本，这些文件按首次补入处理。已有主类 `DemoApplication` 可继续工作，不为对齐新模板做重命名。

业务 Java、业务配置、自定义 POM 属性、业务版本与 Manifest 声明保留。当前参考测试的 `home` 路径断言须恢复为旧工程的 `overview`，不能改业务 Manifest 迁就模板。固定样例复制的是无业务定制的旧测试骨架；真实业务仓库应合并并保留自有业务测试。

## 数据与 TDD 证据

1. 红灯：`/private/tmp/v020-upgrade/02-red-http.log`。实际旧 JAR 先通过 HTTP 创建两租户订单，停止旧进程；候选 JAR 使用同一隔离数据库后，旧订单可读写且权限有效，但既有 `/internal/ops/dead-events` HTTP 查询返回 `500 system.error`。服务日志确认 `forge_dead_event` 不存在。此前完整检查也准确指出旧表字段缺中文注释及参考测试路由预期漂移。
2. 只追加 V8/V9：从**实际候选 SDK JAR** 提取死信表建表/注释/索引，原 Outbox/消费去重表不重建；从实际参考工程提取仍存在的五张旧表的中文注释，不改数据/约束。旧七条迁移保持字节一致。
3. 绿灯：`/private/tmp/v020-upgrade/02-green/verify-upgraded.log`，升级工程完整 `verify`：**18 tests，0 failures，0 errors，0 skipped**。包含原 17 个 HTTP、租户、功能/Data Policy、架构、schema 测试和一个跨旧/新 JAR 验收。
4. `http-evidence.json`：旧服务 HTTP 创建 A「升级前采购单」金额 128 和 B「另一租户旧单」金额 64；新服务读回原 ID/标题/金额，A 可更新为「升级后继续处理」并创建新单，B 原单仍可读。
5. 业务 HTTP 前后都返回 `existing-business:KEEP-2026:` 前缀，证明自定义控制器与配置共同保留。匿名请求 401、只读成员创建 403、跨租户读取/更新 404、无效请求 400。Ops 令牌读取死信 200，Tenant Token 调用该入口 401。
6. Flyway 公共接口记录 `historyBefore=7`、`historyAfter=9`，前七条的版本、脚本、checksum、安装时间、执行账号完全一致。`baseline.json` 记录原/升级文件摘要；SQL、Manifest 与环境示例在格式化后仍字节一致，Java 允许 Spotless 格式变化并以 HTTP 验证业务语义。

| 本票新增迁移 | SHA-256 |
| --- | --- |
| `V8__forge_dead_event.sql` | `2467fd0dfac69176e61a1f717fd65e974fd9cd9a5594b17a122d2c968b8a636a` |
| `V9__existing_schema_comments.sql` | `349d935b0fefe098ccc4a22b334b6c624da15ab612e109f81f03005fc10da9c9` |

实际候选 MQ JAR SHA-256：`8c27d480d7fc22521868342bab6ad6461d6618f9711fb5b089d38012be05242b`。未修改旧 V1—V7，未执行 clean/repair，未覆盖业务数据或重建数据库规避迁移。旧/新服务各自启动和停止，数据库及 Redis 在本轮期间保持同一实例；测试结束由 Testcontainers 回收本轮资源。

## 复现与诊断

```sh
bash scripts/release/candidate.sh use /private/tmp/v020-upgrade/candidate-54115992 \
  bash deploy/upgrade-smoke/business-service.sh /private/tmp/v020-upgrade/02-reproduce \
  /private/tmp/v020-upgrade/02-green/published
```

证据目录必须尚不存在。省略最后一个缓存目录时从实际发布仓库重新只读下载，需 `gh` 的包读取授权或 CI `GITHUB_TOKEN`（`packages: read`）。入口不请求或接受外部数据库目标。当前机器完整 Maven/容器测试经 `python3 /private/tmp/v020-upgrade/run-heavy.py <命令...>` 串行执行；该临时锁不进入产品仓库。

主要证据：`baseline.json`、`http-evidence.json`、`reference-diff.patch`、`published/provenance.json`、`generate-old.log`、`generate-reference.log`、`build-old.log`、`resolve-upgraded.log`、`build-upgraded.log`、`verify-upgraded.log`、`runtime-old.log`、`runtime-upgraded.log`；目录同时保留 `old/`、`reference/`、`upgraded/` 工程与旧/新服务 JAR。早期下载/夹具诊断位于 `02-red*`；macOS 子 JVM 已改为当前测试 JVM 的绝对 Java 路径。

轻量检查通过：Python AST、Shell 语法、`git diff --check`；入库 Java 夹具与实际通过 Spotless/编译/测试的文件完全一致。篡改旧发布缓存的 JAR 会在进入 Maven 消费前因 SHA-256 不符被拒绝。生成工程 Wrapper 单独确认可执行并报告 Maven 3.9.14。

## 验证边界

- 当前实测 JDK 17.0.12、Python 3.14.4、Maven Wrapper 3.9.14、macOS、Docker Desktop；PostgreSQL 17、Redis 7，测试类沿用 SharedContainers 的独立数据库/Redis 库约定。系统 Python 3.9 的一次 HTTPS EOF 不作为包权限失效；使用可用 Python 3.14 后实际读取成功。
- 本票使用现有 TestTokens/JWKS 与 Redis Membership Grants 接缝，通过真实签名 JWT、真实服务 HTTP 与真实数据库验证权限；不把它称为完整 IAM 登录、网关、Operator 登记或前端 Shell 验收。01 已单独覆盖新工程真实平台链路；已有工程前端由 03 验证。
- HTTP 验收关闭 MQ/Nacos/Manifest 远程上报及租户生命周期写保护；Outbox 正常落库，但不代表真实 Broker 投递、死信重放或真实平台凭证轮换已验收。
- 默认 starter 不引入 IO/storage；04/05 分别验证可选能力的旧数据与行为，两个试点按自己的实际基线验收。未操作共享/生产数据库，未上传目标制品仓库，未切换共享环境，不宣称发布就绪。
- 候选包内容变动后须另建候选并复验受影响部分。本票仅新增消费脚本/用例与升级文档，继续使用 `54115992` 包身份；不能将本票脚本提交当作制品源码提交。
