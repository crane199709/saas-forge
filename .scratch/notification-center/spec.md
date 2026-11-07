# 通知、公告与待办规格

Status: resolved

术语以 [GLOSSARY.md](../../GLOSSARY.md) 为准。本规格的决策经 2026-11-07 的设计拷问逐条确认。

## 1. 背景与目标

目前只有 `ContactSender` 发送验证码、邀请和到期提醒，Membership 没有站内收件箱，也没有统一的待办入口。

目标：Business Service 可发 Notification、管理 Todo；Tenant Admin 和 Operator 可发布 Announcement；`tenant-app` 提供统一入口。

非目标：邮件和短信扇出、SSE 推送、订阅偏好、消息模板和多语言内容、审批工作流、平台运营端收件箱。

## 2. 架构

- 宿主为 `forge-iam` 的独立功能包，写入压力确实出现时再拆分。
- 新增 `forge-sdk-notification`，经 Outbox 发事件，由 `forge-iam` 消费，必须在业务事务内调用。
- 内容为纯文本标题、正文和链接（Sub-App 编码 + 路由）。

## 3. Notification

- 目标是 Membership 列表，或在创建时按 Permission Code 展开为当时的持有者。不属于该 Tenant 的 Membership 被忽略。
- 使用按月分区的非标准表，保留 180 天后删除分区（[ADR-0022](../../docs/adr/0022-notification-partitioned-non-standard-table.md)）。
- 同一 Identity 在不同 Tenant 的通知互不可见；已离开 Tenant 的 Membership 不能再访问。

## 4. Todo

- 业务服务按（服务、业务键）幂等开启和关闭 Todo；一条 Todo 可有多个处理人，关闭后对所有人关闭。
- **Outbox 不保证顺序：** 事件携带发生时间，IAM 只采用发生时间更晚的状态。关闭事件先到时也要留下记录，迟到的旧开启事件不能把它重新打开；之后发生的新开启可以重新打开同一业务键。
- 目标为 Membership 列表或 Permission Code。Permission Code 在**读取时判断**，权限被收回或超出 Plan 后立即看不到。
- Sub-App 不在当前 Plan 内时隐藏。标准表、逻辑删除。

## 5. Announcement

- Tenant Admin 发布到本 Tenant（权限 `announcement:manage`）；Operator 发布到全部或指定 Tenant（权限 `platform:announcement:manage`）。
- 面向全部 Tenant 的公告，对生效期内新建的 Tenant 同样可见。
- 支持可选的到期时间；可撤回，撤回后不再显示。已读状态按 Membership 记录。
- 审计：`announcement.publish`、`announcement.revoke`。

## 6. 界面

`tenant-app` 头部铃铛显示未读数，每 60 秒轮询，页面不可见时暂停。列表页包含通知、公告和待办。公告管理在两端各有页面。

## 通用验收

- 后端用集成测试覆盖；涉及租户数据时必须覆盖跨租户行为，用户可见流程补 Playwright。
- 遵守 `docs/agents/coding-standards.md`；新表用 `COMMENT ON` 写中文描述。
- 新增 SDK 表时，同步更新服务迁移、`forge-service-archetype`、`docs/business-service-guide.md` 与 `CHANGELOG.md`。
- 带租户数据的新表接入租户清理流程（`TenantDataCleaner`）。

## 实现与验收

五张票均已解决，全部实现汇总于 `codex/notification-center-integration`。通知、公告与待办的后端、两端界面、租户隔离和真实 MQ 业务闭环已验收；规范审查的两个必修项已修复并复核关闭，规格审查无遗留项。

完整结果见 [集成验收](validation.md)，接口与逐票红绿记录见 [01](validation-01.md)、[02](validation-02.md)、[03](validation-03.md)、[04](validation-04.md)、[05](validation-05.md)；另有 [通知 RLS 验收](validation-rls.md) 和 [审查处理](review-resolution.md)。
