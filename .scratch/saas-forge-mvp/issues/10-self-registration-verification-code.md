# 10: 自主注册与验证码登录

**What to build:** 新用户可以在 tenant-app 自主注册 Identity（注册后尚未加入任何租户），也可以用手机或邮箱验证码登录。短信与邮件发送是可插拔 SPI，开发环境默认实现把验证码打印到日志。

**Blocked by:** 03 Tenant Realm 最小登录链路

**Status:** ready-for-agent

- [x] 用户名/邮箱/手机号全局唯一；注册需验证邮箱或手机
- [x] 验证码有有效期、发送频率限制与错误次数上限
- [x] 手机/邮箱验证码登录与密码登录产出相同形态的 Token
- [x] 短信与邮件发送为 SPI，提供开发用日志实现
- [x] 新注册、无 Membership 的 Identity 登录后进入「无租户」页
- [x] tenant-app 提供注册页与验证码登录方式
- [x] 测试覆盖注册、重复注册、验证码过期、超频与错误次数上限


## Comments

2026-10-27 实现与验收：
- 新增 `verification` 功能包：验证码只存 Redis（`iam:global:verification:` 前缀），默认有效期 5 分钟、一次性使用，同一目标 60 秒发送间隔（不区分注册与登录），错误 5 次即作废；比较与计数在同一个 Lua 脚本中原子完成。发送失败时回滚发送间隔。
- 短信与邮件发送为两个独立 SPI（`SmsSender`、`EmailSender`），声明 Bean 即替换；未声明时用日志实现输出验证码，并在启动时写 WARN。
- 新增 `registration` 功能包：`/auth/register/send-code`、`/auth/register`（201）。账号、邮箱（不区分大小写）、手机号均为全局唯一，冲突返回 409。V6 迁移把邮箱改为可空，新增带部分唯一索引的 `phone` 列，并约束邮箱和手机至少填一项。注册不建立 Membership。
- 验证码登录：`/auth/login/send-code`、`/auth/code-login` 建立与密码登录相同的登录会话，之后走同一套授权码 + PKCE 流程，签发的 Token claims 与密码登录一致。对未注册目标同样生成验证码、计入发送间隔，但不发送，避免借此探测账号。
- 网关和 IAM 已放行这 4 个匿名端点。`@forge/sdk` 新增 `sendLoginCode`、`loginWithCode`、`sendRegistrationCode`、`register`。tenant-app 登录区提供密码登录、验证码登录、注册三种方式；注册后用同一账号密码自动登录，进入无租户页。
- 验证结果：
  - 后端 `./mvnw verify` 通过；审查修复后重跑 IAM 测试，共 41 项通过，其中 `RegistrationTest` 10 项，覆盖注册、重复注册、验证码过期、超频、错误次数上限、Token 一致性、防探测和发送失败。
  - 前端 test（tenant-app 51 项）、typecheck、build、format:check 均通过。
  - Playwright 9 项回归通过。
  - 未进行三服务真链路验证。
- 已知边界：
  - 限频只按目标计算。按 IP 或租户的限流，以及注册查重可被用来探测账号，留待工单 11 评估。
  - 手机号不做国家码归一化。
  - 前端收到 429 时不启动倒计时，只展示服务端提示。
