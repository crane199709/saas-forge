# 04：Announcement 验证与 API 契约

实现分支：`codex/notification-announcement`。实现提交：`5f2d83c`；已通过 `0e2294c` 合并 integration 快照 `f504a17`。迁移：`backend/forge-iam/src/main/resources/db/migration/V24__announcement.sql`。

## HTTP 契约

以下路径是 IAM 服务路径，经网关时统一加 `/iam` 前缀。所有响应使用 `ApiResponse`；创建成功 HTTP 201，其他成功 HTTP 200。列表直接放在 `data` 数组中，不分页，按发布时间及标识倒序。

| Realm | 方法与路径 | 用途与权限 |
| --- | --- | --- |
| Tenant | `GET /announcements` | 当前 active Membership 可见的未到期、未撤回公告 |
| Tenant | `GET /announcements/{id}` | 单条当前可见公告，不可见统一返回 `announcement.notFound` / 404 |
| Tenant | `GET /announcements/unread-count` | `data: {count: number}`，仅统计当前可见且未读的公告 |
| Tenant | `POST /announcements/{id}/read` | 按 Membership 幂等标已读；不可见公告 404；成功 `data: null` |
| Tenant | `GET /announcements/manage` | 本租户自行发布的全部公告，含已到期与已撤回；要求 `announcement:manage` |
| Tenant | `POST /announcements/manage` | 发布到当前租户；要求 `announcement:manage` |
| Tenant | `POST /announcements/manage/{id}/revoke` | 幂等撤回本租户自行发布的公告；要求 `announcement:manage`，其他来源或租户的标识 404 |
| Platform | `GET /platform/announcements` | 全部平台公告管理列表，含已到期与已撤回；要求 `platform:announcement:manage` |
| Platform | `POST /platform/announcements` | 发布平台公告；要求 `platform:announcement:manage` |
| Platform | `POST /platform/announcements/{id}/revoke` | 幂等撤回平台公告；要求 `platform:announcement:manage`，租户公告标识 404 |

发布请求共有字段：`title`（必填纯文本，最长 200）、`body`（必填纯文本，最长 20000）、`subApp`（可空，最长 64）、`route`（可空，最长 2048）、`expiresAt`（可空的 ISO-8601 时间）。已有到期时间早于当前时间的公告可进入管理历史，但不会对成员显示。

平台发布另含 `allTenants: boolean` 和 `tenantIds: UUID[]`。`allTenants=true` 时目标列表必须省略或为空；`false` 时必须非空、全部租户已登记，重复标识被去重；非法组合或未登记目标返回 `announcement.invalidTarget` / 400。全部租户目标不预展开，因此生效期内新建租户同样可见。

租户读取与管理响应：`{id, source: 'tenant'|'platform', title, body, subApp, route, expiresAt, publishedAt, revoked, read}`。租户管理列表的 `source` 固定为 `tenant`，`read` 固定为 `false`（管理视图不代表收件状态）。

平台管理响应：`{id, source:'platform', title, body, subApp, route, expiresAt, publishedAt, revoked, allTenants, tenantIds}`，不含 `read`。

发布与首次撤回产出 `AuditEvent`：`announcement.publish` / `announcement.revoke`，category 为 `operation`，resourceType 为 `announcement`，resourceId 为公告标识。租户事件 realm=`tenant`、带当前 tenantId 和 Identity actor；平台事件 realm=`platform`、tenantId 为空、actor 为 Operator。

两端安全链各自验签，跨 Realm Token 返回 401；缺少管理权限返回 403；非 active Membership 返回 401。租户/平台权限码分别登记在 IAM 两份 Resource Manifest 中，版本分别升级为 6 / 4。

## 结构与边界

- `announcement` 功能包独立承载 controller / service / mapper / model；跨功能包只访问服务。
- `tenant_announcement`、`announcement_read` 均为标准表并启用 ENABLE/FORCE RLS。IAM 的公告事务从可信 `TenantActor` 设置 `app.tenant_id`，XML 仍显式限制租户，保持两道隔离。
- `platform_announcement` 是平台全局标准表；`platform_announcement_target` 是全局投放目标关联。
- `AnnouncementCleaner` 经 SDK 的 `TenantDataCleaner` 处理 `TenantDeleted`，逻辑删除本租户公告和已读状态，仅移除该租户目标关联，不删除平台公告本身或其他目标。

## 验证记录

测试边界采用用户已确认的 IAM HTTP、事件消费与 SDK 公共事件出口。数据库只用于标准测试 fixture 准备，不查询业务表验证实现；审计从公共 `EventPublisher` 出口捕获。

- 红 01：`./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest`，公告入口尚未实现，未读查询无数据；日志 `/private/tmp/announcement-red-01.log`。
- 绿 01：同命令，1 个 HTTP 用例通过，验证本租户可见、跨租户拒绝、Membership 已读隔离与幂等；日志 `/private/tmp/announcement-green-01.log`。
- 红 02：`./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest#platformAnnouncementsReachAllOrSelectedTenantsAndTheTwoRealmPermissionsStaySeparate`，平台发布预期 201、实际 403；日志 `/private/tmp/announcement-red-02.log`。
- 绿 02：`./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest`，2 个用例通过；日志 `/private/tmp/announcement-green-02.log`。
- 红 03：`./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest#revokedAndExpiredAnnouncementsDisappearButManagementAndAuditKeepThePublishedFacts`，详情接口未实现，断言失败；日志 `/private/tmp/announcement-red-03.log`。
- 绿 03：`./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest`，3 个用例通过，覆盖撤回/到期隐藏、管理历史、重复撤回仅审计一次、两 Realm 审计载荷；日志 `/private/tmp/announcement-green-03.log`。
- 红 04：`./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest#allTenantAnnouncementsReachNewTenantsAndCleanupRemovesOnlyTheDeletedTenantsData`，新租户和独立已读已通过，清理后仍看到租户公告而失败；日志 `/private/tmp/announcement-red-04.log`。
- 绿 04：`./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest`，4 个用例通过；日志 `/private/tmp/announcement-green-04.log`。
- 验收补充与门禁：`./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest,ArchitectureTest,SchemaCommentTest`，6 个公告用例与 2 个门禁共 8 项通过；日志 `/private/tmp/announcement-gates.log`。新增验收覆盖无权 Operator、非 active Membership、非法目标组合及空标题。
- `git diff --check` 通过。
- 合并后重新执行 `./mvnw -o -pl forge-iam -am install -DskipTests`，成功（16.075 秒），日志 `/private/tmp/announcement-install-after-merge.log`。
- 合并后执行 `./mvnw -o -pl forge-iam test -Dtest=AnnouncementTest`，6 个公告用例全部通过，耗时 20.192 秒；日志 `/private/tmp/announcement-after-merge.log`。

按协调顺序执行过 `./mvnw -o -pl forge-iam -am install -DskipTests`，构建成功；每轮变更先运行 `./mvnw -o -pl forge-iam spotless:apply`。

IAM 模块全量和两端 Playwright 由集成负责人及票 05 统一执行；本票不宣称已运行。
