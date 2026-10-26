# 04: 迁移 IAM 与 demo-service 接口

**What to build:** IAM 的 `/auth/*` 与 demo-service 的全部接口改为返回统一信封，业务错误改用功能包 `ErrorCode` 与 `ForgeException`；demo-service 订单列表演示统一分页与排序。不保留旧格式。

**Blocked by:** 02 信封模型与 forge-sdk-web

**Status:** ready-for-agent

- [ ] IAM 引入 `forge-sdk-web`；`SessionController` 的 login、memberships、switch、exchange、refresh、logout 返回 `ApiResponse<T>`，保留现有 `Cache-Control: no-store`；logout 由 204 改为 200 且 `data` 为 null
- [ ] 删除 IAM 的 `AuthenticationErrors`；`auth.failed`（401）与 `auth.tenantAccessDenied`（403）定义在功能包 `service` 层的 `ErrorCode` 枚举中，错误密码与未知账号仍返回完全相同的响应（`traceId` 除外）
- [ ] IAM 的 `/oauth2/*` 与 OIDC 端点不受影响
- [ ] demo-service 的 `OrderController`、`ContextController`、`GreetingController` 返回 `ApiResponse<T>`；创建返回 201，删除返回 200
- [ ] `OrderNotFoundException` 改为 `ForgeException` + `OrderErrorCode.NOT_FOUND`（`order.notFound`，404）；跨租户访问与不存在的订单响应仍然无法区分
- [ ] 订单列表 `GET /orders` 支持 `page`、`size`、`sort`（白名单至少含 `createdAt`、`amount`），返回 `meta.page`；SQL 遵守编码规范
- [ ] 业务错误码的 `zh-CN` 文案写入各服务 `messages.properties`
- [ ] 更新 [登录说明](../../../docs/tenant-login.md) 中的接口表：响应为信封，logout 返回 200
- [ ] 测试：更新 `TenantLoginTest`、`TenantIsolationTest` 等对响应体的断言；新增分页默认值、上限、非法排序字段与 `meta.page` 的测试；后端 `./mvnw verify` 通过
