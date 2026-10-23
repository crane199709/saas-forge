# 认证基于 Spring Security 内置 Authorization Server 自建 OAuth2.1/OIDC

使用 Spring Security 7 内置的 Authorization Server（Boot 4 起已并入 Spring Security）签发 JWT；业务服务经 SDK 用 JWKS 本地验签，无需每次回调 iam；Redis 承载会话、Refresh Token 与吊销信息。选择标准协议是为了平台控制台与业务端共用 SSO，并为将来第三方应用接入留路。

## Considered Options

- Sa-Token：上手快，但非标准协议，业务服务本地验签与第三方接入需要自造。
- Keycloak 等外部 IdP：Identity × Membership × 双 Realm 的模型需大量定制，且多一个重型依赖。
