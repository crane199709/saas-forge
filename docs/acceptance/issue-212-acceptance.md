# Issue #212：Project 创建与详情验收

日期：2026-09-30。范围：[Issue #212](https://github.com/crane199709/saas-forge/issues/212)，父需求为 [#211](https://github.com/crane199709/saas-forge/issues/211)。实现与测试随本记录所在提交交付。

## 交付内容

新增独立 [Project Example 模块](../../examples/project-service/README.md)，只交付创建与详情。其 [OpenAPI](../../examples/project-service/openapi.yaml) 固定字段长度、不可写字段、UUIDv7、三位毫秒 UTC 时间、Location、资源版本及错误语义，并明确后续修改/删除的 `If-Match` 约定。

业务只通过 Starter `TenantContextAccessor` 获取已验证上下文，使用独立数据库、Flyway 迁移、MyBatis XML 与受限账号。没有业务 Token 解析或底座数据库访问。运行时包不包含 Flyway、JUnit 或 Testcontainers，应用不接收迁移凭据。原生启动说明直接使用 IDE Run/Debug，不要求完整 Compose、进程托管、JAR 启停或本地配置模板。

验收发现 Starter 的预处理校验错误缺少 `errors`，本次仅补全 `VALIDATION_FAILED`、`UNTRUSTED_CONTEXT_HEADER` 的响应格式；未改变认证、CORS、Cookie 或 Token 授权边界。

## 验收映射

主证据为 [ProjectHttpIT](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectHttpIT.java) 的真实 HTTP/数据库测试；契约证据为 [ProjectContractTest](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectContractTest.java)。没有用 Controller Mock、手工写入 Security Context 或注入 TenantContext 替代主验收。

| #212 验收项 | 结果及对应证据 |
| --- | --- |
| 创建/详情正式契约、字段、Location、资源版本 | 通过：契约解析引用公共 Schema；公开 HTTP 验证 `201 → Location → 200`、相同资源、UUIDv7、版本 1、固定时间格式、可选描述及重复名称。验证名称/描述长度、系统字段、NUL、非字符串输入、非法 JSON、非法 UUID 与 406/415。 |
| 独立服务与 Starter 公共上下文 | 通过：启动正式 Spring Boot 应用和 Tomcat；业务只注入 `TenantContextAccessor`；主测试通过受控 JWKS 与正式 Starter 链建立上下文。原生说明与运行包检查通过；未实际连接开发者的 Nacos/IAM 环境。 |
| A/B 自有操作、跨 Tenant 不可见及身份拒绝 | 通过：A/B 分别创建和读取；跨 Tenant 与不存在资源统一 404。匿名、平台、Service Access Token、非法 Token、错误签名、Tenant Header/查询/Body 别名被拒绝。Redis 撤销索引失去 ready 时失败关闭，恢复后可重新读取。 |
| 独立迁移及受限数据库账号 | 通过：真实 PostgreSQL 18 上执行正式 bootstrap 和 V1–V4。两个 Tenant 表均非空 tenant_id，ENABLE/FORCE RLS 与 USING/WITH CHECK；运行账号非 owner、非 superuser、无 BYPASSRLS/继承，不能 SET ROLE 到 migrator、禁用 RLS 或 TRUNCATE。 |
| 同事务设置 app.tenant_id、无筛选隔离 | 通过：创建与读取均经事务级 set_config；直接运行账号读写证明不依赖 Tenant WHERE。缺失/空上下文无可见数据，非法上下文读取报错关闭；空/非法/其他 Tenant 上下文无法插入跨 Tenant 数据，两张表均覆盖写边界。 |
| 连接提交与回滚后的 Tenant 清理 | 通过：应用连接池限制为一条，核对 `pg_backend_pid()` 一致；创建提交、注入数据库写失败回滚、切换 A/B 和无上下文直接查询均无前一事务 Tenant 泄漏。 |
| UUIDv7 幂等、24 小时、并发及失败 | 通过：同键重放原状态/正文/Location，修改请求冲突；真实并发 HTTP 的首事务被数据库锁阻塞时，重复请求返回 409 与 Retry-After。23 小时仍重放，25 小时可重新创建。字段 400 不占键；数据库失败回滚后原键可成功使用。稳定业务失败仅有受控完成记录重放证据，见下述限制。 |
| 同一 Identity 跨 Tenant 复用键 | 通过：全 Identity 键空间不含 Tenant，切换 Tenant 同键返回冲突且不返回旧正文；基础设施事务回滚后原键可用于 B。过期跨 Tenant 键在维护前拒绝，执行正式清理 SQL 后可重新使用。 |
| 后续版本传输及安全范围 | 通过：契约记录单个强 `If-Match` 版本、缺失/过期语义；只暴露创建与详情，不添加后续操作，不修改 Gateway/CORS 白名单。 |
| 契约、构建、迁移、HTTP、数据库测试 | 相关检查通过；完整运行的初始失败及补跑分别记录如下。不改写已执行迁移，V3/V4 为前向修正。 |

## 执行结果

| 检查 | 结果 |
| --- | --- |
| Example 契约单测 | 通过：1 个，0 失败/错误/跳过。 |
| Example HTTP 与 PostgreSQL 集成 | 通过：10 个，0 失败/错误/跳过。真实 Starter、Tomcat、PostgreSQL、Redis；包含过期维护 SQL。 |
| Starter 全部已有回归 | 通过：27 个单测与 4 个 Redis HTTP 集成测试。对格式修复另行定向回归 TenantContext、HTTP 认证过滤器与 Redis HTTP，共 16 个通过。 |
| 根项目完整 `verify` 首次运行 | 失败：已执行 715 个测试，1 个失败、0 错误/跳过。失败是 RepositoryStandardsTest 把既有、Git 忽略的历史源码备份当成当前持久化交付物。另有 Example 编译失败：运行期间审查修复新增 MyBatis 依赖，先前加载的 Maven 模型没有该依赖；随后重新加载模型修复并通过。其余 Reactor 模块通过。 |
| 质量门禁补跑 | 通过：33 个，0 失败/错误/跳过。用当前已跟踪文件与已暂存新增文件生成临时源码快照，排除本机忽略产物；生成 JAR/覆盖率使用同次工作区产物。包括仓库规范、v1 兼容、SDK 发布、覆盖率、基础设施和 PostgreSQL 边界全部 7 个测试类。没有降低或跳过门禁。 |
| 双轴审查 | Standards 首轮 3 项、Spec 首轮 2 项，均修正；复审两个轴均 0 未解决发现。 |

Example 最终验证命令：

```sh
./mvnw --batch-mode --no-transfer-progress -pl examples/project-service -am verify \
  -Dtest=ProjectContractTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ProjectHttpIT -Dfailsafe.failIfNoSpecifiedTests=false
```

根项目完整运行使用 `./mvnw --batch-mode --no-transfer-progress --fail-at-end verify`。质量门禁补跑选择全部质量测试类：

```sh
./mvnw --batch-mode --no-transfer-progress -pl saas-forge-quality-gates -am verify \
  -Dtest=RepositoryStandardsTest,JavaSdkOpenApiPublicationTest,V1ContractCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=CoverageThresholdIT,InfrastructureContainersIT,JavaSdkReleaseBoundaryIT,PostgreSqlDataBoundaryIT \
  -Dfailsafe.failIfNoSpecifiedTests=false -DrepositoryRoot=<交付源码快照的绝对路径>
```

临时快照与日志不作为长期交付物或后续验收依据；以上记录、版本化测试和迁移是可重现证据。完整运行本身的状态仍为失败，不能表述为原工作区单次完整 verify 全绿。

## 证据范围与限制

- 认证依赖使用测试专用签名密钥、HTTP JWKS 和 Simple Discovery 设施；Starter 签名/Claim/撤销验证及 PostgreSQL/Redis 均为真实实现。这不等于真实 IAM/Nacos 部署联调或浏览器验收。
- 当前创建在字段校验通过后没有自然业务 4xx：允许同名，且不接入 Quota/成员权限。本次受控既存失败记录验证了稳定状态、正文的重放与跨 Tenant 隐藏，没有编造额外业务拒绝规则，也不声称测试了不存在的业务失败产生流程。
- 为保持 RLS，跨 Tenant 的已过期键在迁移账号维护前仍返回键冲突；正式过期清理 SQL 已测试。运维必须定期执行清理，维护频率决定额外占用时间。应用不持有迁移账号，未增加安全定义函数或跨 Tenant 读写特权。
- 未执行 Gateway、Remote、Console、Chrome、完整 Fresh Compose 或生产环境验证，均为本票明确非目标。本记录不勾选父 PRD 的完整 Project/Task CRUD 或 MVP 阶段 3。
