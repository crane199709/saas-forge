# Issue #165：Entitlement 原生启动验收

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

## 实现范围

- Entitlement 增加个人 local 模板、文件 Config Data 与 JAR 排除配置；保留 Flyway 关闭，敏感值仍通过环境变量或受限文件提供。
- local 下 Entitlement → IAM HTTP/JWKS/服务 Token、IAM gRPC Platform Role、Tenant Access gRPC 资格查询，以及 Tenant Access → Entitlement Quota 均复用内部服务发现模块。
- 非 local 保留原有 Spring gRPC 通道配置及生命周期，不改正式业务 API、数据库结构或测试/生产安全策略。
- 新 dev 初始化补齐最小 naming 读取权限及 ACL 校验，未自动修改已运行环境权限或接管应用。
- 使用步骤见 [开发说明](../native-entitlement-development.md)。

## 自动化证据

配置测试先因缺少个人模板失败，补齐后通过。HTTP 通信测试通过真实临时服务器验证同一客户端跟随目标端口变化及空健康列表拒绝；Tenant Eligibility 与 Quota 测试先因缺少对应发现通道 Bean 失败，再补齐实现。测试中的 Nacos 为外部边界替身，HTTP/gRPC 为真实本机通信，不代表真实 Nacos 业务联调。

初次通信测试受沙箱 Mockito attach 限制，随后获准运行；Nacos 测试装配问题单独修正。开发途中未跟踪文件被清理，用户确认后已恢复本轮文件。

- 聚焦配置、真实临时 HTTP/gRPC 通信及相关装配测试通过，Maven 退出码 0。
- `bash scripts/validate-nacos-config.sh`、初始化/ACL 脚本语法和 `git diff --check` 通过；未修改环境 Nacos YAML 资源。
- Standards 审查：0 项发现。Spec 审查：1 项未完成的现场验收，见下文。
- `mvn -q -pl gateway,services/iam-service,services/tenant-access-service,services/entitlement-service -am verify` 退出码 0。本次报告共 516 项，失败/错误/跳过均为 0：Gateway 44、IAM 260、Tenant Access 110、Entitlement 66、内部发现 11、Auth SDK 21、Route Catalog 4。只统计本轮日志创建后生成的报告。
- Entitlement 发布 JAR 已检查，不含个人 local 配置或模板。
- 本机日志：`/tmp/issue165-focused.log`、`/tmp/issue165-verify.log`。临时数据库/Redis 故障测试日志不等同于测试失败，以 Maven 退出码及 JUnit 报告为准。

## 现场验收进度（2026-09-10）

开发者在 IDEA 启动 Entitlement 后，`http://127.0.0.1:8083/actuator/health/readiness` 返回 `{"status":"UP"}`。使用各工作负载身份读取真实 dev Nacos，得到以下健康注册：

| 查询身份 | 目标 | 注册 IP | HTTP | grpc.port |
| --- | --- | --- | --- | --- |
| Entitlement | IAM | 127.0.0.1 | 8081 | 9091 |
| Entitlement | Tenant Access | 127.0.0.1 | 8082 | 9092 |
| Tenant Access | Entitlement | 127.0.0.1 | 8083 | 9093 |

此前本地 `.env` 提供的 Entitlement 凭据登录失败，且 Tenant Access 读取 Entitlement 返回 403。经开发者明确授权，复用已停止开发容器的工作负载凭据到 Git 忽略的受限 configtree，并仅补齐 Entitlement → IAM、Entitlement → Tenant Access、Tenant Access → Entitlement 三项 dev naming 读取权限；随后登录及发现查询均成功。未重置数据、重建身份或启停容器。

以上仅证明启动、健康与注册发现，不代表业务联调通过。仍待完成 IDEA 重启、真实 Tenant 初始化及 Quota 副作用、真实 Nacos 端口变更和无健康实例演练。

仓库完整 CI、Fresh Compose 和多浏览器矩阵未执行。Issue #165 尚不能据此声明全部验收通过或关闭。

## 2026-09-13 原生 Console 业务补验

开发者确认 Gateway、IAM、Tenant Access、Entitlement 已由 IDE 启动，前端已就绪。IAM、Tenant Access、Entitlement readiness 均返回 200/UP；Gateway 的同路径返回 404，不将该探针计为通过，随后真实 Console 业务正常。

使用现有 Chrome 登录会话，经 `https://platform.saas.forge.test` 的正式页面：

- 复用已激活 `local-development` Plan（`01a08096-58a2-7c4f-bf00-eda6a204089c`），max_users 上限 1。
- 创建专用 Tenant `issue165-native-20260913-0922`，ID `01a09a12-c184-787a-bc36-cdc49f4fed6c`，初始状态为待初始化。
- 创建首个 Subscription `01a09a13-466b-70c3-89bf-aad627e82d37`，页面权威读取 ACTIVE、查询时有效、max_users 已用量 0。
- 使用专用测试邮箱完成管理员初始化；Tenant 变为已激活，初始化已完成，历史 Membership 为 `01a09a13-c756-79c6-aa68-f97c01aee5ae`，权威 max_users 已用量变为 1。
- 刷新完整页面后，2026-09-13 17:23:18（Asia/Shanghai）的权威查询仍为上限 1、用量 1，Tenant 与 Subscription 状态保持一致。通知独立显示待投递，不作收件箱送达声明。

本轮没有直接重放原初始化请求，不能将刷新读数不变等同于幂等重放验证。仍待开发者操作 Entitlement 端口变更/IDE 重启、无健康实例拒绝及恢复演练；Issue 暂不关闭。浏览器连接最初因工具请求头策略加载失败受阻，重连成功后完成上述操作；该工具故障不计为应用故障。

### 首次端口变更检查：未通过

开发者重启后，新 HTTP 8183 readiness 为 200/UP，旧 8083 拒绝连接。Chrome 点击“重试权益读取”出现 NETWORK_UNAVAILABLE，保留此前已确认的 Subscription/Quota 信息。

以 Tenant Access 既有工作负载身份只读查询真实 dev Nacos：Entitlement 仍注册 `127.0.0.1:8083`、healthy=true，metadata `grpc.port=9193`。个人配置中 `server.port` 默认值已改为 8183，但 `spring.cloud.nacos.discovery.port` 默认值仍为 8083；未同步实例自身监听和注册端口。本次尚不能证明端口切换成功，需要开发者统一 `ENTITLEMENT_HTTP_PORT=8183` 后重启并复验，不修改调用方下游地址。

### 修正注册端口后复验：通过

开发者修正 Entitlement 自身注册端口并重启，未要求修改或重启调用方。真实 dev Nacos 的健康实例为 `127.0.0.1:8183`，metadata `grpc.port=9193`。原 Tenant 点击“重试权益读取”后错误消失，权威查询时间更新到 17:43:38，用量仍为 1。

随后从同一 Chrome 正式 Console 新建 `issue165-port8183-20260913`（Tenant ID `01a09a27-2014-7832-af05-efd6a914ab8f`），复用原 Plan。首个 Subscription `01a09a27-8843-73f8-b839-d53415c58165` 创建成功，ACTIVE、查询时有效，初始化前权威用量 0。管理员初始化成功后 Tenant 已激活、初始化已完成，历史 Membership `01a09a27-c502-7823-a61e-d6de8496eba7`；17:44:52 的权威 max_users 用量为 1。证明变更自身 HTTP/gRPC 端口并正确注册后，真实订阅、初始化及 Quota 协作可通过发现完成。此前注册错误保留为失败轮次，未计为通过。

无健康实例演练与恢复原端口复查尚待执行。

### 无健康实例现场演练

开发者仅停止 Entitlement。以 Tenant Access 工作负载身份查询真实 dev Nacos，Entitlement 健康实例列表为空；8183/9193 TCP 均拒绝连接。原 Chrome 页面点击“重试权益读取”，明确显示 `权益暂时无法读取 NETWORK_UNAVAILABLE`，仍保留此前已确认的 Subscription ACTIVE、上限 1、用量 1 及旧查询时间，不伪造成功或空权益。随后独立重新读取 Tenant 成功，状态仍为已激活、初始化已完成、历史 Membership 不变。

本轮验证的是权益正式读取在无健康目标时失败，不是初始化写入中断或原 Key 重放。通知独立回读为“通知需处理”，不将其归因于 Entitlement 停机，亦不影响已确认激活事实。待恢复原端口后复查权益与业务状态。


### 恢复原端口：通过

开发者恢复 Entitlement 原端口并重启。真实 Nacos 返回唯一健康实例 `127.0.0.1:8083`、`grpc.port=9093`，readiness 200/UP。同一 Chrome 页面重试权益读取后 NETWORK_UNAVAILABLE 消失，权威查询时间更新为 2026-09-13 17:51:40，用量仍为 1，Subscription ID 与初始化历史 Membership 不变。全程 IDE 应用生命周期由开发者管理，未自动替换服务或修改调用方下游实例配置。

本次现场覆盖正常初始化、HTTP/gRPC 端口切换后的新初始化，以及无健康实例时的正式权益读取失败和恢复；未执行停机期间初始化写入及原 Key 重放，不将读取重试冒称写入重放。原 Key 幂等、真实配额耗尽及补偿已有 #174/#176 服务集成与 Chrome/Fresh 证据。发现异常、无健康目标和非法 gRPC metadata 另以服务发现聚焦回归验证，不中断共享 Nacos。

### 关闭核对（2026-09-13）

- 本轮服务发现聚焦回归：`./mvnw -pl services/service-discovery -am -Dtest=NacosServiceEndpointsTest,DiscoveredGrpcChannelTest -Dsurefire.failIfNoSpecifiedTests=false test -q` 退出码 0；2+9=11 项，失败/错误/跳过均为 0。`git diff --check` 通过。
- 产品代码提交 `ed9b49dbb6bad52d9d11b8a3c88df1c617d9f408` 的 [Verify 34747247761](https://github.com/crane199709/saas-forge/actions/runs/34747247761) 已重新查询为 completed/success；其 Chrome/Fresh 产物在 #170 关闭时已下载并核验同 SHA、dirty=false、全阶段 passed。当前 HEAD `314d7dbf07ac8d18396838a0fef1074a781b844e` 相比该 CI 提交仅有三份 #170 文档修改，本轮也仅更新 #165 文档；不声称 314d7db 或本轮未提交文档有新的 CI。
- 验收 1、2、5：已有个人模板、原生说明、服务发现实现与自动化证据，加上开发者本轮 IDE 启停/变更端口/恢复实操。
- 验收 3：两组专用 Tenant 均通过真实原生 Console 完成首个 Subscription 与管理员初始化，权威用量由 0 变 1。
- 验收 4：真实 Nacos 从 8083/9093 变为 8183/9193 后新业务成功；停机健康实例为空、读取明确失败，恢复原端口后同一读取成功。发现异常及 gRPC 边界由上述聚焦回归补充。
- 验收 6：使用说明补充自身 HTTP 监听/注册端口一致要求；保存实际成功、配置错误、拒绝及恢复记录，未修改正式 API、数据库或测试/生产安全边界。

六项验收均有对应证据，前置 #164 已关闭，满足 #165 关闭条件。该结论不自动关闭父 #161。上述早期“待完成”段落保留当时事实，最终状态以本节为准。两组专用验收资源保留，未执行删除或数据库清理。
