# Issue #205：公司工作台与双身份切换

状态：2026-09-20，后端切换与 Client 候选制品已实现并完成下列定向验证；前端适配、正式 Client 发布和真实 Chrome 业务验收未完成。不得据此关闭 Issue。

## 后端实现

- 正式 v2 契约新增 `selectConsoleContext`，用于首次选择和平台/公司/公司间切换。请求只接受平台目标或 Membership ID；Tenant ID 由权威 Accessible Membership 解析，Platform Role 不授予 Membership。
- 当前上下文及目标权限均重新核验。当前上下文失效结束整个当前 Family；目标无权只拒绝目标，初始凭据禁止选择。
- 切换保留 Identity、Family 和原会话期限，推进 Context Version。相同目标无副作用；切换记录绑定 Slot、幂等键和目标指纹，原键稳定重放，换目标拒绝。
- 先提交数据库上下文、旧 Token 持久撤销和 `SWITCH_PENDING`；在 Slot 锁内确认撤销交付后进入 `CONTEXT_REFRESH_REQUIRED`。这段锁防止并发恢复在另一请求完成并刷新后误撤销新 Token。交付失败保留已提交事实，允许原键恢复或退出。
- 未 refresh 前 session/contexts/新切换拒绝；refresh 只恢复已选择上下文。新选择要求未消费的当前 Refresh Cookie，旧 Cookie 不作为新切换凭据。
- 当前和候选公司响应包含 Tenant Brand Profile；历史 v1 契约和 Flyway 迁移未修改。

## 验证记录

JDK 17。HTTP 用例使用隔离 Testcontainers PostgreSQL 18、Redis 和 Kafka，并经真实 Tenant Access gRPC/数据库查询权威 Membership。没有操作开发数据库或接管开发服务。

已通过：

- `AuthenticationHttpIT#unified*`：8 项，其中新增 3 项，覆盖双身份初选、平台/公司双向切换、公司间切换、Token 撤销、刷新恢复、同目标与原键重放、品牌响应、租户 Token 拒绝平台 API、无权目标、非法字段、过期 revision、幂等键冲突、撤销故障恢复及当前 Membership 失效。既有无权限/初始凭据用例补充选择拒绝。
- `RefreshTokenFamilyTest`：7 项，包含新增的切换不改变 Identity/期限及非法目标/受限会话拒绝。
- `MyBatisRefreshTokenFamilyRepositoryTest`：12 项既有持久化回归。
- `UnifiedConsoleContractTest`：1 项，新 operation 已纳入自动生成的路由目录。
- `RepositoryStandardsTest`：19 项，包含正式契约、路由所有权及凭据边界一致性检查。
- 正式 OpenAPI 生成 TypeScript Client、TypeScript 编译、打包；在仓库外临时目录安装本地 `0.3.0` tarball，检查生成 operation 的路径、方法、If-Match、目标和浏览器管理凭据不被手工注入。
- `git diff --check`。

执行命令：

```sh
mvn -pl saas-forge-services/iam-service,saas-forge-quality-gates -am test \
  -Dtest='AuthenticationHttpIT#unified*,RefreshTokenFamilyTest,MyBatisRefreshTokenFamilyRepositoryTest,UnifiedConsoleContractTest,RepositoryStandardsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn -o -pl saas-forge-services/iam-service -am test \
  -Dtest=RefreshTokenFamilyTest -Dsurefire.failIfNoSpecifiedTests=false
```

首次 HTTP 验证有一处测试把租户 Token 用在仅平台会话查询并错误期望 200；改为对应租户上下文查询，并另断言租户 Token 调用平台业务 API 返回 403 后通过。一次扩展检查因 Maven 依赖下载 TLS 握手失败未执行，重试后通过。首次 npm pack 在生成/编译成功后被本地缓存写权限阻止，使用临时 npm 缓存完成打包，未改系统权限或依赖版本。

## 尚未完成的交付与验收

- `@crane199709/saas-forge-api-client@0.3.0` 仅为本地候选，来源标记为 `dirty=true`，不得将此次 tarball 作为正式发布证据。正式发布要求先提交审阅后的源码，再重新构建，核对干净来源并发布。
- 独立前端仍精确使用已发布 `0.2.0`，未修改为兄弟仓库、file/link 或临时 HTTP 调用。按父 PRD 顺序，正式 Client 和兼容后端可用后再升级前端。
- 尚需在 saas-forge-web 实现分组公司卡片、租户工作台、显式切换、多标签及脏表单协调、晚到响应隔离、权威品牌原子应用、路由边界与中英文/键盘/焦点验证。
- 尚需开发者启动兼容后端；前端连接该环境，用真实单身份及双身份账号完成 Chrome 业务证据。当前 HTTP 测试不代替真实 Console、Gateway 或全部直达实例验收。
- 未执行完整 CI、完整 AuthenticationHttpIT、Fresh Compose 或其他专项完整矩阵。
