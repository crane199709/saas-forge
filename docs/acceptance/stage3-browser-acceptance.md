# 阶段 3：真实 Tenant Shell / Project Remote 验收

状态：2026-10-06，本轮隔离 Fresh Compose 与桌面 Google Chrome 产品路径通过。只覆盖阶段 3 最小闭环；不代表阶段 4–9、远端 CI 或生产发布完成。

## 环境与来源

- runId：`4558f7d2-2296-4377-be69-b3de59c4d641`；项目网络及 PostgreSQL、Redis、Kafka 卷独立，从空卷开始。初始化、配置和修复均只发生在该项目。
- 最终 handoff SHA-256：`59e738c45f6615afdc2c4445480df24210018ef07376a9e9cf26c23ffb8feac3`。七应用、JDK 17、项目资源归属、Gateway/受信 HTTPS Edge 的 JWKS 一致性通过。
- Chrome `154.0.8037.98`；正式 Console/API/Remote 域名、受信 HTTPS。浏览器自行管理 Cookie、Origin、Fetch Metadata，无忽略 TLS 或注入业务凭据参数。
- 后端验收基线 `6866345356dbe328451a97005a8c4e3be90f9954`，dirty=true；本轮发现的修复构建后更新 Remote Delivery/Tenant Access 镜像，最终镜像摘要见附带的 handoff。前端基线 `aa67f6b771c73558d56a9af68c352341b21d7648`，dirty=true；最终浏览器报告保存源码摘要及锁文件摘要。不能把源码 HEAD 当作干净发布制品。
- 正式 Client `@crane199709/saas-forge-api-client@0.5.0`，来自已发布提交 `d436c3c4bcb80519b50751155e081d086dc1e34b`；无 file/link 替代。
- Project Remote `1.0.0`：SHA-256 `acc9d5d5e709ee109dbbbeeb33ae1717d9ae3db4930055e6d9448eab64366209`；共享 `vue@3.5.31;element-plus@2.13.6`。Edge 使用固定只读交付目录，不挂载会被构建清理的 dist 目录。

公开报告与 handoff 位于 [stage3-browser-evidence.json](stage3-browser-evidence.json)。前端持有浏览器操作脚本和本仓副本报告，后端不导入前端源码或执行其浏览器门禁。

## 实际产品路径

准备通过真实平台页面完成首次改密、仅含 Manifest 注册 Scope 的 CI Client、max_users 定义、套餐、两个 Tenant/Subscription、管理员初始化及 Mailpit Password Setup。CI Secret 仅写受限文件；它不进入 Remote 或验收报告。准备操作不能代替下面的浏览器业务路径。

| 检查 | 本轮直接结果 |
| --- | --- |
| CI 注册 | 正式发布 Client，经 HTTPS Gateway 注册 Project 1.0.0，持久状态为 PENDING_REVIEW |
| 平台审核/启用 | Playwright 驱动真实 Chrome 页面批准、启用；租户随后加载真实业务 Remote |
| 未审核/未启用 | PENDING_REVIEW 和 APPROVED 两个状态下，租户页面均显示“暂无已启用的业务模块” |
| 拒绝 | 1.0.1 从平台页面拒绝，显示 REJECTED 且无启用按钮；已启用 1.0.0 不被替换 |
| 不受控来源 | CI 注册外部 Origin 被正式接口以 400 REMOTE_SOURCE_NOT_ALLOWED 拒绝；平台没有该来源声明 |
| Project/Task CRUD | Tenant A 通过业务 Remote 创建、列表、详情、更新、删除，实际状态码为 201/200/204，版本从 v1 到 v2 |
| Tenant 隔离 | A 列表不包含 B；Project GET/PUT/DELETE，Task GET/PUT/DELETE，以及在 B Project 下 POST Task 共 7 项返回 404；B 回读 Project/Task 仍为原值、v1 |
| 缺失 Tenant Context | 平台上下文浏览器请求 Project operation 返回 403 |
| Remote 加载失败/恢复 | 浏览器网络层阻断真实入口下载，业务组件不存在，Shell 显示错误；解除阻断后点击重新读取恢复 |
| Locale/品牌 | 真实页面切换英文后 Project、Task 表单和 Done 状态更新；Remote 显示当前 Tenant 名称 |
| 核查期间状态 | 35 秒自动化检查与 68 秒人工驱动检查均保留未提交输入；Host 在 checking 状态拒绝调用，核查失败/上下文变化卸载组件 |
| 可访问性/键盘 | Remote axe 检查无违规；平台页 WCAG 2 A/AA、2.1 AA 无违规；真实键盘输入可用，英文桌面页面已目视检查 |

隔离负向操作只在 Playwright 网络边界改写资源 ID；令牌、请求体、版本条件、正式 Client、Gateway、Starter 和 RLS 都是真实的，未伪造响应。缺失 Context 同样由平台会话的正式读请求改写 operation 地址触发。它们是浏览器篡改请求的安全验收，不冒充普通 UI 提供外租户 ID 输入框。来源拒绝是实际 CI API 验证；入口哈希和 UI 版本非法场景另有前端单元测试，不伪称真实浏览器已启用非法 Manifest。

## 日志、Trace 与审计

同轮 Chrome 写操作经 Project Outbox → Kafka → Audit 消费者落库。对每次核心重跑生成的 A Project/Task ID 精确查询，六种 `PROJECT_CREATED/UPDATED/DELETED`、`TASK_CREATED/UPDATED/DELETED` 各有一条成功审计记录。拒绝的跨租户写入没有改变 B 数据。

`validate-application-logs.py` 使用已批准的 jsonschema 校验三个服务实际输出；Collector 校验实际 Server → Server → Producer → Consumer Span、相同 trace ID 与逐级 parent ID。最终计数和六事实查询结果写入附带 JSON；这些日志与 API/数据库检查补充浏览器操作证据，不替代浏览器验收。

## 本轮发现并修复

- Remote Delivery readiness 组引用了不存在的注册就绪组件，补齐实际 Nacos 注册事件检查。
- Compose 的模块授权环境变量命名不符合 Boot 绑定规则，增加直接读取 Compose 变量的绑定回归；未知 Client 仍默认拒绝。
- Manifest Problem 的 `about:blank` 被 Gateway 归一为 502，改为公共 URN 契约并验证 400/403 响应。
- Tenant Access 原 Membership gRPC 只允许 IAM；落实已批准的指定 Remote Reserved 身份读取，其他 Client、错误 Scope、撤销/不可判定状态仍拒绝。
- Shell 的监听及 checking 状态切换导致 Remote 重载、丢草稿；保留同一会话/租户的组件，核查期间隐藏，Host 继续禁止调用。
- 空环境漏开统一 Console 开关、Kafka 初始化匿名卷、重建 dist 导致 Edge 旧挂载失效均已修正。前置失败属于本轮诊断，不计为通过。

定向检查：Remote HTTP/部署绑定 2 项通过；Tenant Membership gRPC/Redis/拦截器 15 项及配置 4 项通过；前端 240 项单元测试、类型、ESLint、生产构建通过；七服务实际 Nacos 最小权限通过；Compose 布局 8 应用/5 场景通过。完整远端 CI 未执行。隔离环境由准备方保留，未删除历史环境/卷，未推送或发布生产环境。
