# Starter 默认身份检查验收（Issue #182）

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

范围依据 [Issue #182](https://github.com/crane199709/saas-forge/issues/182)。业务服务引入 Starter 并提供服务登记、IAM issuer、Nacos Discovery 和 Redis 配置，即使用统一认证；测试接收端原有的手写签名、JWKS 与撤销适配器已删除。平台与业务由同一批可信厂商开发者维护，Tenant 不自行开发或部署业务代码。

## 实现与证据映射

| 条目 | 实现与验证接缝 |
| --- | --- |
| Resource Server、RS256、Route Catalog | Starter 使用 Spring Security BearerTokenAuthenticationFilter，继续复用 sdk-auth 的算法、签名、issuer/audience、时间、Claim 和 User/Service 类型检查；原 HTTP 路由及 Scope 错误矩阵保留 |
| JWKS 缓存、合并、限频、限时 | IamJwksHttpTest 使用真实 HTTP JWKS、真实 RSA 签名、可控时钟和并发同步；验证五分钟到期、默认十秒刷新间隔、默认两秒等待、随机 kid、并发合并、已有公钥请求不阻塞、失败不续期及恢复 |
| Redis fail-closed | RedisAuthenticationHttpIT 使用 Redis 8.8.1；验证 jti/kid、Membership/Tenant Fence、client_id、Ready 缺失、MGET 被 ACL 拒绝及恢复；拒绝时不能进入业务处理 |
| 只读上下文 | TenantContextHttpTest 与既有 HTTP/公开 API 测试验证平台、租户、服务上下文互斥、不可写、异常清理、连续请求和子线程无串用；保留头、Tenant query/JSON 输入被拒绝，正常 JSON 可重放 |
| 认证前输入有界 | USER JSON 默认最多 1 MiB，`saas.forge.authentication.max-json-bytes` 可受控调整；超过上限返回 413，包括没有凭证的请求 |
| 缺配置与依赖恢复 | 自动配置测试验证缺少必需配置启动失败；HealthEndpoint readiness 必含认证检查，依赖故障为 DOWN，liveness 不加入认证依赖；可控 HTTP 测试验证 IAM/Redis 恢复 |
| 真实机制链路 | `verify-platform-mechanism-e2e.sh` 创建独立 Compose 项目、卷和临时密钥；真实 IAM、Redis、Nacos、Gateway、两个仅用默认 Starter 的接收端；覆盖 Gateway 与直连、用户/服务、Scope、Ready、客户端吊销、发现与故障切换 |
| 真实 IAM 换钥 | 同一专项中发布两个公钥，再由真实 IAM 使用新私钥签发；新旧凭证均验证成功，旧 kid 撤销后旧凭证失败、新凭证成功；浏览器会话通过正式 refresh 换取新用户凭证 |

JWKS 只通过 Nacos 发现的 `iam-service` 获取，不接受 Token 提供的地址。缓存更新失败不延长有效期；命中缓存仍读取撤销状态。公钥响应在网络接收时限制为 64 KiB，快照最多 64 个有效 RS256 公钥，不保存随未知 kid 输入增长的负缓存。

换钥专项只操作隔离数据库中的生命周期状态与临时开发私钥，将 `published_at` 设为五分钟前以模拟完成发布窗口，并按既有公式设置 RETIRING 保留时间。它证明真实签发/JWKS/接收端之间的轮换行为；不声称实际等待了五分钟或整个旧密钥保留期，不验收生产 KMS 或新增密钥管理 API。生命周期约束由既有 IAM 测试补充。

## 执行记录（2026-09-14）

环境：macOS aarch64、Oracle JDK 17.0.12、Maven 3.9.14；隔离 Compose 使用仓库固定的 PostgreSQL 18、Redis 8.8.1、Nacos 3.1.1 等镜像。未接管开发者的 IDE 或本地应用进程。

| 命令 / 检查 | 结果 |
| --- | --- |
| `bash scripts/verify-platform-mechanism-e2e.sh` | PASS，全部九阶段和新增 6b 换钥阶段完成，脚本退出 0，隔离资源清理完成；本机日志 `/tmp/starter-mechanism-complete.log` |
| `./mvnw --batch-mode --no-transfer-progress -Pbackend-local,sdk-external-consumer-acceptance verify` | PASS，27 个 Reactor 模块，709 项测试，0 failures/errors/skipped，耗时 6:58；日志 `/tmp/starter-backend-full.log` |
| Starter HTTP / 启动 / Redis 子集（包含在上项） | PASS，Tenant Context 6、JWKS 8、自动配置 5、路由过滤器 6、真实 Redis 4 项测试 |
| 外部消费者与公开发布面（包含在全量命令中） | PASS，外部消费者 6 项，JavaSdkReleaseBoundaryIT 4 项，CoverageThresholdIT 通过 |
| `./mvnw --batch-mode --no-transfer-progress -Pbackend-local,platform-mechanism-acceptance -pl test-support/platform-mechanism-receiver -am -Dtest=PlatformMechanismReceiverControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS；默认 Reactor 外的专用接收端控制器测试，日志 `/tmp/starter-receiver-test.log` |
| `bash -n scripts/verify-platform-mechanism-e2e.sh`、`git diff --check` | PASS |
| 前端完整工作区、浏览器、远端 CI | 未执行；本次为后端认证范围，`backend-local` 只跳过前端聚合门禁，不跳过后端单元、集成和契约检查 |

上述记录对应本次实现工作树，不冒称推送后 SHA 的 CI 结果。早期 TDD 曾分别复现缺少默认适配、重复 JWKS 获取、未知 kid 不刷新、readiness 未注册、外部 Tenant 输入未拒绝；修复后相关测试通过。扩展真实专项时还发现重复登录活动 Browser Session Slot 返回 409，已改为正式 refresh，再完整重跑通过；早期失败不能作为通过证据。

## 审查与边界

Standards / Spec 两项独立审查原先指出认证前无界读取 JSON，以及缺少真实启动故障/换钥证据。已增加有界读取、HTTP 测试和真实专项场景；最终复核未发现新的阻断性偏差。Redis Ready=0 的独立判断以真实 Redis HTTP 测试为据；紧随 IAM 恢复的 readiness 503 也可能受公钥刷新冷却影响，不能单独归因 Redis。

本项不包含 Project/Task 页面、创建业务、事务级 Tenant 设置、RLS 或第三阶段完整产品浏览器闭环。未修改数据库迁移、Nacos 环境资源或 Redis Key Registry。远端 CI、发布到 Maven Central 和 Issue 关闭不属于本次本地实现结果。

## 仓库重构后复验（2026-09-14）

针对 Maven 模块目录调整、`io.saas.forge` / `saas.forge` 命名统一、原生配置与 `deploy/acceptance` 编排拆分，重新执行验证，不沿用首次实现的通过结论。

最终基线为 `8ba5b2e89940a5015ba9ac3765b3806a43289f93`。后端完整验证开始于 `34896cff3ea0a11dbc7b260bb66f4acf41375af5`，当时工作区已有四个 Console/验收文件修改，随后由其他任务提交为 `8ba5b2e`；两次 HEAD 之间没有后端、Starter 或本专项脚本变更。Compose 布局在该提交后再次校验，真实专项也在该提交下运行。

| 检查 | 本轮结果 |
| --- | --- |
| `./mvnw --batch-mode --no-transfer-progress -Pbackend-local,sdk-external-consumer-acceptance verify` | PASS，713 项测试，0 failures/errors/skipped；158 个测试类报告，耗时 4:49；包含 JWKS HTTP 8 项、真实 Redis HTTP 4 项、外部消费者与 SDK 发布边界检查；日志 `/tmp/issue-182-revalidate-backend.log` |
| `./mvnw --batch-mode --no-transfer-progress -Pbackend-local,platform-mechanism-acceptance -pl test-support/platform-mechanism-receiver -am -Dtest=PlatformMechanismReceiverControllerTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS，专用接收端控制器 1 项；日志 `/tmp/issue-182-revalidate-receiver.log` |
| `bash scripts/validate-nacos-config.sh` | PASS，各环境配置校验通过；日志 `/tmp/issue-182-revalidate-nacos.log` |
| `python3 scripts/validate-compose-layout.py` | PASS，8 个独立应用、5 个验收场景、项目/网络/卷隔离、挂载与迁移门禁；日志 `/tmp/issue-182-revalidate-compose-layout.log` |
| `node --test scripts/test/compose-initialization.test.mjs` | PASS，2 项；验证独立运行与验收组合的数据库/迁移调用归属；日志 `/tmp/issue-182-revalidate-compose-init.log` |
| `bash scripts/verify-platform-mechanism-e2e.sh` | PASS，全部九阶段及 6b 换钥阶段完成、退出码 0；日志 `/tmp/issue-182-revalidate-mechanism.log` |
| 脚本语法、Git diff 格式与旧命名空间检查 | PASS；当前后端/SDK/测试支持的编译目录未发现 `target/classes/io/saasforge` 残留 class |

真实专项通过新的 `deploy/acceptance` 布局验证了 IAM 不可达启动、readiness 恢复、真实用户与服务凭证、Gateway 与直连复验、Scope/Token 类型、Index Ready、IAM 新旧密钥与缓存 kid 撤销、Nacos 扩缩容/无健康实例、客户端吊销及响应和日志泄漏检查。轮换仍使用前文说明的隔离生命周期时间夹具，不扩大为生产 KMS 或实际等待整个保留期的验收。

隔离项目 `saas-forge-platform-mechanism-61020-3888` 结束后，分别读取 Docker 容器（含停止状态）、卷、网络的项目标签列表，结果均为空。没有接管已有开发应用。一次辅助状态查询因使用 Docker 不支持的 `Service` 模板字段失败，改用支持的字段完成读取；该辅助命令错误不影响上述验收脚本结果。

本轮未修改业务代码，仅追加本验收记录；未推送或触发远端 CI。前端完整工作区和浏览器产品验收未执行，本结论限定于 Starter 身份检查及其后端/真实机制边界。
