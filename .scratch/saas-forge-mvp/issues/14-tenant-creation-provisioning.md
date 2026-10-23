# 14: 租户创建与开通

**What to build:** Operator 在 platform-console 创建 Tenant 并填写初始管理员的邮箱或手机号，完成 Provisioning：已存在的 Identity 直接收到邀请，不存在的则创建待激活 Identity 并发送邀请链接；接受邀请后成为该租户的 Owner。platform 通过 Outbox 发出 `TenantCreated`，各服务幂等初始化本服务数据；Tenant 不等待所有服务完成即可用，平台界面展示每个服务的初始化状态，失败可重试。

**Blocked by:** 06 多租户成员与切换租户, 08 Platform Realm 与运营登录, 09 Outbox 与租户感知消息, 10 自主注册与验证码登录

**Status:** ready-for-agent

- [ ] platform 拥有 Tenant 主数据；创建 Tenant 与写入 `TenantCreated` 在同一本地事务
- [ ] iam 消费事件：按邮箱/手机匹配 Identity，存在则创建 `invited` Membership，不存在则创建待激活 Identity；均授予 Owner
- [ ] 邀请经邮件/短信 SPI 发出；接受邀请（新 Identity 需设置凭证）后 Membership 变为 `active`
- [ ] SDK 提供「租户初始化」扩展点：服务实现幂等初始化（如预置本服务角色模板），并回报成功或失败
- [ ] demo-service 实现初始化并回报结果
- [ ] platform-console 展示租户列表、详情与各服务初始化状态，失败项可重试
- [ ] Tenant 创建后直接进入试用或正常状态，不等待全部服务
- [ ] 测试：对同一事件重复消费不产生重复数据；人为制造 demo-service 初始化失败后重试成功
