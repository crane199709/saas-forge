# 14: 租户创建与开通

**What to build:** Operator 在 platform-console 创建 Tenant 并填写初始管理员的邮箱或手机号，完成 Provisioning：已存在的 Identity 直接收到邀请，不存在的则创建待激活 Identity 并发送邀请链接；接受邀请后成为该租户的 Owner。platform 通过 Outbox 发出 `TenantCreated`，各服务幂等初始化本服务数据；Tenant 不等待所有服务完成即可用，平台界面展示每个服务的初始化状态，失败可重试。

**Blocked by:** 06 多租户成员与切换租户, 08 Platform Realm 与运营登录, 09 Outbox 与租户感知消息, 10 自主注册与验证码登录

**Status:** resolved

- [x] platform 拥有 Tenant 主数据；创建 Tenant 与写入 `TenantCreated` 在同一本地事务
- [x] iam 消费事件：按邮箱/手机匹配 Identity，存在则创建 `invited` Membership，不存在则创建待激活 Identity；均授予 Owner
- [x] 邀请经邮件/短信 SPI 发出；接受邀请（新 Identity 需设置凭证）后 Membership 变为 `active`
- [x] SDK 提供「租户初始化」扩展点：服务实现幂等初始化（如预置本服务角色模板），并回报成功或失败
- [x] demo-service 实现初始化并回报结果
- [x] platform-console 展示租户列表、详情与各服务初始化状态，失败项可重试
- [x] Tenant 创建后直接进入试用或正常状态，不等待全部服务
- [x] 测试：对同一事件重复消费不产生重复数据；人为制造 demo-service 初始化失败后重试成功

## Comments

2026-10-28 实现：
- SDK：新增 `forge-sdk-provisioning`，定义 `TenantCreated`、`TenantInitializationRequested`（按服务重试）、`TenantInitializationReported` 三个事件（Topic `forge-tenant`，已加入 `deploy/rocketmq/create-topics.sh`）与扩展点 `TenantInitializer`。服务声明该 Bean 后，SDK 订阅事件，在消费事务内以保存点（`PROPAGATION_NESTED`）执行全部初始化器：失败只回滚初始化数据，失败回报照常提交、消息不再重投，改由 Operator 在平台重试；成功回报与初始化数据同一事务提交。回报中的服务名取 `spring.application.name`。
- platform：V2 新增 `tenant`（主数据，含 `admin_contact`）与 `tenant_initialization`（每服务一行，`pending | succeeded | failed`）及 Outbox 表。`POST /tenants` 在同一事务中写租户、按 `forge.platform.provisioning.services` 预建 pending 状态、以新租户上下文发布 `TenantCreated`；`GET /tenants`（分页）、`GET /tenants/{id}`（含各服务状态）、`POST /tenants/{id}/initializations/{service}/retry`（只允许 failed，状态回到 pending 并发布重试事件）。回报经 `ON CONFLICT` upsert，未预建的服务回报也会记录。平台表在 `forge.tenant.ignore-tables` 中声明为全局表。租户创建即为 `active`（TODO #15 按 Plan 决定 trial/active，iam 与网关目前只放行 active）；Operator 权限校验留给工单 22。
- iam：引入 provisioning starter（随之引入 forge-sdk-tenant），所有 mapper 加 `@InterceptorIgnore(tenantLine = "true")`：iam 的表按 ADR-0001 不走租户行拦截，消费事件时 SDK 恢复的租户上下文不能改写其 SQL。`TenantProvisioningService` 依次：登记租户副本、预置 Owner/Tenant Admin、按邮箱（不区分大小写）或手机号找 Identity（找不到则建 `pending` Identity，V9 让 `account`、`password_hash` 可空，并约束 active 必须有凭证）、建 `invited` Membership、授予 Owner、Membership 仍为 invited 时发邀请。每步可重复执行。邀请令牌 32 字节随机数，Redis 只存其 SHA-256（`iam:global:invitation:`，默认 7 天），链接为 `forge.iam.invitation.accept-url?invitation=<token>`（地址无代码默认值，漏配启动失败）。匿名接口 `POST /auth/invitations/preview`、`POST /auth/invitations/accept`（网关与 iam 安全链均已显式放行）：pending Identity 接受时须设置账号与密码，之后 Identity 与 Membership 均为 active，令牌作废。邮件/短信 SPI 的参数改为封闭接口 `ContactMessage`（`VerificationMessage | InvitationMessage`），新增 `ContactSender` 按目标选择通道；部署方已有的 `EmailSender`/`SmsSender` 实现需按消息类型选择模板。
- demo-service：V6 新增 `order_categories`（RLS + 标准列 + `(tenant_id, code)` 部分唯一索引），`OrderCategoryService` 实现 `TenantInitializer`，只补齐缺少的预置分类。
- 前端：platform-console 登录后展示租户列表、创建对话框、详情抽屉（各服务初始化状态，失败项可重试，需手动刷新查看回报结果）；tenant-app 新增 `/invitations/accept` 落地页，`@forge/sdk` 增加 `previewInvitation`、`acceptInvitation`。
- 验证：platform `TenantProvisioningTest` 3 项（创建与事件同事务、回滚无事件、回报/重试规则）；iam `TenantProvisioningTest` 3 项（已有身份邀请与接受并成为 Owner、新联系方式设置凭证后接受、发送失败回报并重试成功、重复初始化不产生重复 Membership）；demo-service `TenantInitializationTest` 2 项（重复初始化不重复数据、部分写入后失败被回滚并回报、重试只由目标服务执行且成功）。以上按 SDK 消费端的方式直接调用事件处理器，未经真实 RocketMQ；同一事件 ID 的去重由工单 09 的 `OutboxMessagingTest` 覆盖（该测试已补建 `forge-tenant` Topic）。后端 `mvn verify` 全部通过；前端单测 73 项（新增 6 项）、typecheck、format:check、11 项 Playwright 通过。未在完整 compose 环境中经网关与真实 MQ 手动走通全流程。
- 已知限制：接受邀请不要求先登录（持有链接即视为能收到该邮箱/手机的消息）；pending Identity 占用联系方式，此人自主注册会得到 `registration.contactTaken`，须走邀请链接；已初始化过的本地 RocketMQ 需重新执行建 Topic 脚本，否则带 `TenantInitializer` 的服务启动时拉不到 `forge-tenant` 路由而失败。
