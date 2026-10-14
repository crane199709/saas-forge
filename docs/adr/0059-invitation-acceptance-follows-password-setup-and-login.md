# Invitation 接受在密码建立与登录之后执行

2026-10-14 第 4 阶段设计访谈确认：新 Identity 从有效 Invitation 进入首次 Password Setup，完成后登录为与邀请匹配的 Identity，再显式接受邀请；此时才扣减 `max_users` 并创建 Membership。已有密码的受邀者直接登录后接受邀请，接受成功不自动切换 Tenant Context。

本决策调整 [ADR 0006](0006-invitation-activation-uses-compensated-saga.md) 中先扣额度、再建立密码的顺序，以复用 IAM 的独立 Password Setup，并避免在等待用户操作期间占用名额。最终入会仍由 Tenant Access 执行同步补偿 Saga：扣减后的失败只补偿 Quota，已建立的 Identity/密码不回滚，Invitation 保持 `PENDING`。

统一会话没有可用工作上下文时不签发业务 Access Token，因此新浏览器闭环以前置受控启用 v2 为条件，补齐正式认证契约中的受限会话身份验证；业务资源按 v1 增量交付，实现本阶段不自动切换环境开关。不能要求客户端提供 Identity、Cookie 或 Origin 作为业务参数。Password Setup 邮件投递成功以及 `EnsureIdentity` 的首次幂等重放均不能替代当前凭据与登录身份的权威验证。具体契约仍待冻结，不表示 Invitation 流程已实现。
