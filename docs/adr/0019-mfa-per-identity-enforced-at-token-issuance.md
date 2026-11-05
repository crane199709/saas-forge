# MFA 按 Identity 全局认证，租户只在签发 Token 时执行 MFA 要求，丢失验证器只能由平台人工重置

Authenticator 绑定在全局 Identity 上（延续 ADR-0002「凭证属于 Identity」）：绑定后每次登录都必须在 First Factor 之后完成 MFA，不按租户分别补验（step-up），因此一次授权只有「已完成 MFA」或「未绑定、无需 MFA」两种状态。Tenant 只能提出 MFA Requirement，由 iam 在签发进入该 Tenant 的 Token（exchange、switch、refresh）时检查授权是否完成过 MFA，不满足即拒绝签发；新开启的要求因此在下一次刷新时生效，不做即时吊销。持有者同时丢失 Authenticator 与 Recovery Code 时，只能由有权限的 Operator 做 MFA Reset 并写审计；租户管理员不能重置（他们无权改 Identity 的全局凭证），也不提供凭邮箱或短信验证码的自助重置，否则 MFA 会退化为两个 First Factor。

## Considered Options

- 按 Membership 绑定验证器：同一个人加入几个租户就要绑几次，且与全局凭证模型矛盾。
- 进入有要求的租户时 step-up 补验：授权要区分「对哪些租户完成过 MFA」，切换租户的换发路径变复杂；而绑定后每次登录都过 MFA 已能满足所有租户的要求。
- 租户管理员重置成员 MFA：管理员可借此接管成员在其他租户的访问，越过租户边界。
- 凭验证码自助重置：攻击者拿到密码与邮箱即可绕过 MFA。

## Consequences

- 授权（含 Refresh Token 链）须记录本次认证是否完成 MFA，refresh 与 switch 依据它判断，而不是依据登录会话。
- 被要求 MFA 但未绑定的 Identity 在会话中当场绑定后，该会话与当前授权视为已完成 MFA。
- 平台客服需要一个只能按登录标识精确查找 Identity 的入口来执行 MFA Reset，它不展示 Membership，不越过 ADR-0003 中 Operator 不看租户数据的边界。
