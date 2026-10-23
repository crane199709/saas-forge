# 全局 Identity + Membership，Token 只绑定一个当前租户

一个自然人只有一个全局 Identity（持凭证），通过多个 Membership 加入多个 Tenant；先全局认证再选租户。每个 Token 的 claims 只包含一个 `tenant_id` 与 `membership_id`，切换租户即用会话换发新 Token，而不是由请求头临时指定租户——这样所有下游只信任 Token，不存在「Token 属于 A 租户却请求 B 租户」的校验分支。租户管理员只能管理本租户的 Membership，不能修改 Identity 的全局凭证。租户入口使用统一域名，子域名将来只作为预选租户的登录提示，不参与鉴权。
