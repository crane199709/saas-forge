# 审计

工单：[23](../.scratch/saas-forge-mvp/issues/23-audit.md)，术语见 [GLOSSARY](../GLOSSARY.md) 的 Audit Event。审计事件经 Outbox 异步投递（ADR-0008），由 iam 写入按月分区的审计表。

## 链路

```
业务服务 @AuditLog ─┐
iam 登录与权限变更 ──┼─ AuditRecorder ─ forge_outbox ─ RocketMQ(forge-audit) ─ iam AuditEventHandler ─ iam.audit_event（按月分区）
forge-platform ─────┘
```

- `AuditRecorder` 在独立事务（`REQUIRES_NEW`）中写 Outbox，写入失败只记 WARN 日志，不影响业务请求。在事务中调用时等事务提交后才写入，回滚则不记录。
- 消费端按事件 ID 去重；表主键 `(id, occurred_at)` 冲突时也忽略，重投不会产生重复记录。

## Audit Event

| 字段 | 说明 |
| --- | --- |
| `realm` | `tenant` 或 `platform`。Tenant Admin 只看本租户的 `tenant` 事件，持有 `platform:audit:read` 的 Operator 看 `platform` 事件 |
| `tenantId` | Tenant Realm 事件所在的租户；登录失败与退出发生在全局登录会话上，不带租户 |
| `actorId` | Tenant Realm 为 Identity id，Platform Realm 为 Operator id；查询时联表显示账号与名称 |
| `category` | `login`、`permission`、`operation` |
| `action` | 点分，各段 lowerCamelCase，如 `order.create`、`role.permissionsReplace` |
| `resourceType` / `resourceId` | 操作对象 |
| `outcome` / `errorCode` | `success` 或 `failure`；失败时为错误码 |
| `detail` | 补充信息（JSONB），不得含密码、Token |
| `service` / `traceId` | 产出服务（`spring.application.name`）与当时的 traceId |

## 业务操作：`@AuditLog`

引入 `forge-sdk-audit` 后在 Spring Bean 的 public 方法上声明：

```java
@AuditLog(action = "order.delete", resourceType = "order", resourceId = "#id")
ApiResponse<Void> delete(@PathVariable UUID id) { ... }
```

- 正常返回记为成功，抛出异常记为失败并带错误码（`ForgeException` 的错误码；权限不足为 `auth.forbidden`；其他为 `system.error`），异常照常抛出。
- 拦截器顺序为 0，在 `@RequiresPermission`（200）与事务之外，因此权限不足与提交失败都会记录。
- `resourceId` 是 SpEL：按参数名或 `#p0` 引用参数，`#result` 引用返回值（如 `#result.data().id()`）；表达式出错时不记录对象标识。
- 有租户上下文时记为 Tenant Realm 事件，否则为 Platform Realm 事件；操作人取自 Token 的 `sub`。
- 同一类内部的自调用不经代理，不会记录。

demo-service 的订单增删改、forge-platform 的租户创建、状态变更与更换套餐已声明。

## iam 自动产出的事件

| 类别 | 动作 | 何时 |
| --- | --- | --- |
| 登录 | `auth.login` | 成功：换发 Token（进入租户）时，带租户；失败：账号密码或验证码校验失败，不带租户，`detail.account` 为尝试的账号 |
| 登录 | `auth.switchTenant` | 切换租户成功（带新租户）或失败 |
| 登录 | `auth.logout` | 退出登录会话 |
| 权限变更 | `role.create`、`role.rename`、`role.permissionsReplace`、`role.dataPoliciesReplace`、`role.delete`、`membership.rolesAssign` | 租户角色、授权、Data Policy 与成员角色 |
| 权限变更 | `membership.invite`、`membership.suspend`、`membership.resume`、`membership.remove`、`membership.leave`、`membership.ownerTransfer` | Membership 变更 |
| 权限变更 | `platformRole.create/update/delete`、`operator.create/update/disable/enable/passwordReset/rolesAssign` | 平台角色与运营账号（Platform Realm） |
| 业务操作 | `dictionary.itemCreate`、`dictionary.itemUpdate`、`dictionary.itemDisable`、`dictionary.itemEnable` | 租户修改 Extensible Dictionary 的字典项；`resourceId` 为 `服务:字典:字典项编码` |
| 业务操作 | `parameter.override`、`parameter.reset` | 租户覆盖 Parameter 或恢复默认值；`resourceId` 为 `服务:参数编码`，覆盖值记在 `detail.value` |

## AI 服务的动作约定

AI 服务沿用 `category=operation`，不增加新的分类枚举。由 `ai-platform` 产出以下事件，平台审计按现有 `action`、`resourceType`、`resourceId` 查询。租户会话与配置使用 Tenant Realm；平台模型、Skill、MCP 目录维护使用 Platform Realm。

| 对象 | action | resourceType / resourceId |
| --- | --- | --- |
| 会话开始、结束 | `ai.conversation.start`、`ai.conversation.end` | `aiConversation` / 会话 ID |
| 工具调用完成（含失败与拒绝） | `ai.tool.invoke` | `aiToolCall` / 调用 ID |
| 会话内切换模型 | `ai.model.switch` | `aiConversation` / 会话 ID |
| Prompt 发布、回滚 | `ai.prompt.publish`、`ai.prompt.rollback` | `aiPrompt` / Prompt ID |
| Skill 发布、回滚 | `ai.skill.publish`、`ai.skill.rollback` | `aiSkill` / Skill ID |
| MCP 发布、回滚 | `ai.mcp.publish`、`ai.mcp.rollback` | `aiMcp` / MCP 配置 ID |
| 平台目录配置变更 | `ai.model.create/update/delete`、`ai.skill.create/update/delete`、`ai.mcp.create/update/delete`（斜杠表示三个独立动作） | `aiModel`、`aiSkill`、`aiMcp` / 目录项 ID |

每次调用或变更只记录一个完成事件，通过 `outcome` 与 `errorCode` 区分成功、失败、拒绝；会话结束的 `detail.reason` 可使用固定值 `completed`、`cancelled`、`failed`。SSE 请求返回不代表会话结束或工具调用完成，应在实际完成边界显式调用 `AuditRecorder`；普通同步配置接口可用 `@AuditLog`。失败信息使用稳定错误码，不能直接写上游错误正文。

`detail` 仅允许会话、模型、工具、目录项的内部标识，已发布版本号，token 计数、耗时和固定状态。不得写消息正文、Prompt 内容、Skill 内容、工具参数与结果、MCP 连接串、模型端点、HTTP 头、凭据或密钥引用。逐条用户消息和流式片段不产出审计；租户删除后保留不含内容的审计元数据。

## 存储与分区

- `iam.audit_event`（V18）按 `occurred_at` 的 UTC 自然月分区，分区名 `audit_event_YYYY_MM`。
- 运行账号无权建分区：写入前调用迁移账号所有的 `SECURITY DEFINER` 函数 `audit_event_ensure_partition` 确保该月分区存在，进程内缓存已确认的月份。新的一月第一条事件到达时自动建分区，无需定时任务。

## 查询接口

| 接口 | Token | 权限码 |
| --- | --- | --- |
| `GET /iam/audit/events` | Tenant Realm | `iam:audit:read`（Owner、Tenant Admin 默认拥有，可授予自定义角色） |
| `GET /iam/platform/audit/events` | Platform Realm | `platform:audit:read`（超级管理员默认拥有） |

参数：`page`、`size`、`from`（含）、`to`（不含，ISO 8601）、`actor`（账号或显示名包含）、`action`、`resourceType`、`resourceId`。按发生时间倒序。

前端：tenant-app「审计日志」页（`/audit`），platform-console「平台审计」页（`/audit`）。

## 部署

RocketMQ 需要 Topic `forge-audit`（已加入 `deploy/rocketmq/create-topics.sh`）。iam 订阅该 Topic，Topic 不存在时 iam 启动失败。
