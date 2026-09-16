# Issue #204：统一 Console 登录、恢复与整体退出

状态：2026-09-16 按 Issue #204 六项标准核对完成。下文早期验证段保留过程事实，最终结论以本节为准。

## 最终核对

- 后端 v2 六个 operation、Gateway 安全边界和 Client 0.2.0 正式发布完成；独立前端精确安装，真实平台账号登录首页通过。
- 后端真实依赖 HTTP 测试覆盖无权限、初始凭据、旧双槽位清理及撤销失败关闭；Gateway 受控 CORS 预检通过、非受控来源被拒；最终 HTTPS 缺失 CSRF 探针返回 403。真实 Chrome Cookie 属性及凭据不持久化通过。
- 每 Realm Runtime 的恢复、并发刷新、整体退出、换账号及晚到真实 200 响应丢弃均已分别通过自动化及 Chrome 主链验收。
- 真实撤销测试账号平台角色后，两页隐藏旧身份，旧 Token 返回 401；精确恢复角色后旧 Token 仍无效，重新登录正常。测试角色已恢复、Membership 仍为零。
- Soybean 正式登录及首页、英文/深色主题、键盘提交、退出确认与定时校验后的焦点保留均已验收。多上下文与初始凭据保持受限，后续业务流程按关联票承接。
- 后端与前端独立记录证据；前端未管理后端进程。前端验收记录截至提交 `6b3ebd1`，后端实现截至 `a7f98d6`，Client 制品来源 `c904af13ad92f2ef6eafd98c7679628b09291405`。后端进程未提供构建哈希，记录工作树基线而不冒充运行制品证明。

范围限制：未执行完整 CI、全部直达服务实例的制品核验及其他专项 Issue 的完整安全矩阵；不以关闭 #204 代替这些专项验收。前置 #203 已关闭。


## 本轮实现边界

- 新增 `/api/v2/auth/bootstrap`、`login`、`session`、`contexts`、`refresh`、`logout` 六个正式 operation；v1 契约保留。
- Cookie 使用 `__Host-sf_console_slot` 与 `__Host-sf_console_refresh`，仅接收受控 Console Origin；变更操作要求 CSRF、版本条件及对应操作幂等键。
- 平台单一上下文直接签发；初始凭据只返回 `sessionId/revision/state`；无权限与多上下文仅返回受限会话，不签发业务 Token。公司工作台、上下文选择及切换交互由 #205 承接。
- 新 Token 带协议、会话及上下文版本标识。受控切换后，验证端拒绝旧协议 Token。
- 退出与刷新重放先提交持久撤销事实，再交付 Redis。退出失败保留可重试状态；历史已完成退出的重放不清除新会话 Cookie。
- 独立前端使用 Soybean 登录外观、布局、菜单、主题及国际化；凭据仅在单 Realm Runtime 内存中。跨标签页使用 Web Locks 与 BroadcastChannel；持久化仅限未完成退出意图。

## 受控切换

新增开关 `security.browser.console-enabled` 默认关闭，不允许动态刷新。切换是一次不可逆的旧协议退役，不能通过关闭开关恢复旧会话。

1. 在维护窗口部署包含新验证器与 V24 迁移的版本，排空旧 IAM 签发请求和旧 Console；先确认所有 Gateway 与直达服务验证端能够拒绝旧协议 Token。配置按现有受控发布机制生效，不经 Console 热更新。
2. 所有参与实例以同一切换决定开启该开关；只有验证端全部生效后才开放 Console 入口。原生开发由开发者在个人忽略配置或 IDE VM 参数中维护，不修改仓库为其提供配置模板。
3. 首次 v2 bootstrap 将 `iam_console_protocol.legacy_blocked` 持久置位。数据库触发器先排空进行中的旧签发事务，再阻断旧 Family、Refresh 与 Access Issuance 的新插入，覆盖尚未退出的旧实例。
4. bootstrap 每次撤销最多 100 个历史 Family，提交数据库与 Outbox 后逐个确认 Redis；成功记录 `console_retired_at`。失败或尚有剩余批次返回 503，不创建新版 Slot；恢复后重试 bootstrap。新登录在全部完成前也保持关闭。
5. 旧 Cookie 对应 Family 的撤销确认后才清 Cookie。结束态通过原 Slot 和幂等键恢复，不以清理浏览器 Cookie 冒充服务端撤销成功。

该流程会终止全部旧浏览器会话，需要重新登录。数据库置位后，不支持回退到仅支持旧协议的服务版本；回退代码也不能绕过持久签发门禁。

## 验证记录

已通过：

- 统一 Console 的 5 个 HTTP 集成用例：真实 PostgreSQL 18、Redis、Kafka 与进程内 Tenant Access gRPC；覆盖登录、刷新、退出重试、历史退出重放、初始凭据、无权限、旧会话清理，以及撤销写入边界故障后的重放持久撤销。使用生产 Console 配置和 Controller/Filter 装配的子 WebApplicationContext。
- SDK User Access Token 验证器 7 个用例，包含强制新协议、旧 Token 拒绝、缺失或畸形会话与上下文版本字段。
- 前端 Runtime 6 个用例，包括只读检查不刷新、退出失败重试、晚到刷新丢弃、同幂等键恢复、登录进行中退出及协调事件后清除旧刷新尝试。
- Client 的 v1/v2 联合生成与 TypeScript 编译；前端针对生成声明的临时类型预检。它不能代替正式 npm 安装验收。

- 完整 `AuthenticationHttpIT`：61 项通过。旧密钥撤销用例恢复数据库 fixture 后同步清除精确测试 kid 撤销键，避免污染后续正向鉴权；未改变生产撤销语义。
- 后端全量单元测试共 510 项：首次仅 RepositoryStandardsTest 的 v1-only 路由统计失败；扩展到 v1/v2 的 62 个 operation 和 Cookie 凭据要求后，该测试类 19 项全部通过，其余 491 项已通过。
- 后端 Standards 与 Spec 双轴复审无未解决代码问题；前端焦点保留修复和真实浏览器验收继续推进。

未执行：正式 npm Client 0.2.0 发布及独立前端精确版本安装、生产构建、真实 Chrome 多标签页/HTTPS Cookie/权限撤销联调、真实 Gateway 与全部直达服务的协议切换验收、完整 CI。npm 初次登录检查返回 401，用户重新登录后身份已确认；等待验证和提交完成后发布。用户已确认迁移并重启本机后端；联调时 IAM v2 bootstrap 仍为 404，已请开发者确认受控开关生效。未代替开发者管理应用进程。

## 原生启动回归修复

2026-09-16，用户启用受控开关后发现 ConsoleAuthenticationController 的 final 声明阻止 Spring CGLIB 方法校验代理。将真实类代理校验加入 HTTP 测试装配后，定向用例先复现相同启动异常；去掉 Controller 的 final 后，全部 5 个统一 Console HTTP 用例通过。未关闭方法校验或修改迁移，仍需用户重新启动 IAM 后继续真实 HTTPS 验收。

## 正式发布与当前验收缺口

Client `@crane199709/saas-forge-api-client@0.2.0` 已在 npm 正式发布，来源提交 `c904af13ad92f2ef6eafd98c7679628b09291405`，dirty=false；独立前端已精确安装，15 项测试、类型检查、ESLint 与生产构建通过。后续提交 `a7f98d6` 修复 IAM 错误响应 trace ID，复用既有请求追踪逻辑，ControllerAdvice/Filter 两条拒绝路径定向回归通过；不改变 Client 契约。

Chrome 153.0.8010.48 已经真实 HTTPS Console 完成用户凭据提交。实际账号有平台权限和 1 个公司上下文，login/session 返回 200，刷新后保持 CONTEXT_SELECTION_REQUIRED；Cookie 的 HttpOnly、Secure、SameSite=Strict 与 Console 无可读 Cookie 已核验。用户确认没有仅平台权限的测试账号，因此平台首页、首页恢复、多标签整体退出和换账号主链尚未完成验收。未调整既有账号授权，不据此关闭 #204。
