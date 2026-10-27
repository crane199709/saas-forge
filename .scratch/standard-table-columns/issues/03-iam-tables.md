# 03: IAM 表补齐标准列

**What to build:** IAM 的 `tenant`、`identity`、`membership`、`operator` 补齐标准列，唯一约束改为部分唯一索引，已删除的身份、成员关系和运营账号不能再参与登录与租户选择。IAM 目前没有实体类，全部 SQL 都写在 mapper XML 中，所以标准列和 `NOT deleted` 都按 spec 第 9 节手工维护。

**Blocked by:** 02 SDK 统一填充与逻辑删除，demo-service 落地

**Status:** ready-for-agent

- [x] 新增 Flyway 迁移：四张表补齐五列，存量行回填
- [x] 唯一约束改造：`identity.account`、`identity_email_lower_key`（`lower(email)`）、`membership(identity_id, tenant_id)`、`operator.account` 改为 `WHERE NOT deleted` 的部分唯一索引，旧约束删除；`identity_email_format` 等 CHECK 约束保留
- [x] `IdentityMapper.xml`、`MembershipMapper.xml`、`OperatorMapper.xml`、`TenantMapper.xml` 中所有查询，对涉及的每张表（包括 JOIN 进来的表）都加上 `NOT deleted`
- [x] `TenantMapper.xml` 的 `updateStatus` 同时写 `updated_at = now()`；`updated_by` 按调用场景填写，没有认证上下文时为 NULL
- [x] demo seed SQL（`R__demo_identity.sql`、`R__demo_operator.sql`）和测试 fixture 依靠列默认值仍能执行；如果其中依赖了旧约束名（比如 `ON CONFLICT ON CONSTRAINT`），一并调整
- [x] 测试：
  - `deleted = true` 的 identity 无法登录，并且返回的响应与未知账号相同
  - `deleted = true` 的 membership 不会出现在租户列表中，也不能选择或切换
  - `deleted = true` 的 operator 无法登录 Platform Realm
  - 账号被逻辑删除后，可以再插入同名账号
  - 现有 IAM 测试全部通过
- [x] 后端 `./mvnw verify` 通过

## Comments

2026-10-27 实现：新增 `V5__standard_columns.sql`，`tenant`、`identity`、`membership`、`operator` 各补五列，存量行靠列默认值回填；`identity_account_key`、`identity_email_lower_key`（`lower(email)`）、`membership_identity_id_tenant_id_key`、`operator_account_key` 删除后以同名重建为 `WHERE NOT deleted` 的部分唯一索引，`identity_email_format` 与 `account` 的 CHECK 不动。`IdentityMapper.xml` 的 `findCredential` 加 `NOT deleted`，`activeMembership` 片段对 `identity`、`membership`、`tenant` 三张表都加 `NOT deleted`（`findActiveMemberships`、`findForRefresh` 共用）；`OperatorMapper.xml` 两个查询加 `NOT deleted`。`TenantMapper.updateStatus` 写 `updated_at = now(), updated_by = NULL`：唯一调用方 `TenantStateService.changeStatus` 目前没有经认证接口的入口（只有测试调用），按系统操作处理，等有管理接口时再改为传入操作人。Mapper 文档注释同步说明「未删除」语义。demo seed 与既有 fixture 用的是不带冲突目标的 `ON CONFLICT DO NOTHING`，无需改动。
- 偏离：`MembershipMapper.xml` 的 `deactivate`、`incrementVersion`（`<select>` 包裹的 `UPDATE ... RETURNING`）不在工单列表里，但按编码规范「手写 UPDATE 自己维护 `updated_*`」一并补了 `updated_at = now(), updated_by = NULL`，理由同上；它们与 `updateStatus` 的 `WHERE` 也加了 `NOT deleted`，已删除的行视为不存在（返回 null / 0，调用方抛既有的 NotFound 异常）。`updated_*` 的写入没有单独测试：IAM 没有能读出这两列的接口，只能经数据库旁路断言。
- 验证：新增 fixture `deleted_identities.sql`、`deleted_memberships.sql`、`deleted_operators.sql`、`reused_identity.sql`、`reused_operator.sql` 与 5 项测试：已删除身份（密码正确且有 active Membership）按账号和邮箱登录，失败信封与未知账号逐字相同；已删除的 Membership 和已删除租户中的 Membership 不出现在登录后与 `/auth/memberships` 的列表中，不能在 exchange 中选择，也不能 switch；已删除 Operator 登录 Platform Realm 返回 401；身份的账号、邮箱（仅大小写不同）与同一 (identity, tenant) 的 Membership 被删除后可重新插入，新身份能用自己的密码登录并选中新 Membership，用旧行的密码则与未知账号相同；Operator 账号同理。每项均先红后绿（红灯分别为缺列、多出已删除项、200、新行被旧约束挡住）。`forge-iam` 31 项测试全部通过；完整 `./mvnw verify` 通过（含 spotless，268 项测试）。
