# 03: IAM 表补齐标准列

**What to build:** IAM 的 `tenant`、`identity`、`membership`、`operator` 补齐标准列，唯一约束改为部分唯一索引，已删除的身份、成员关系和运营账号不能再参与登录与租户选择。IAM 目前没有实体类，全部 SQL 都写在 mapper XML 中，所以标准列和 `NOT deleted` 都按 spec 第 9 节手工维护。

**Blocked by:** 02 SDK 统一填充与逻辑删除，demo-service 落地

**Status:** ready-for-agent

- [ ] 新增 Flyway 迁移：四张表补齐五列，存量行回填
- [ ] 唯一约束改造：`identity.account`、`identity_email_lower_key`（`lower(email)`）、`membership(identity_id, tenant_id)`、`operator.account` 改为 `WHERE NOT deleted` 的部分唯一索引，旧约束删除；`identity_email_format` 等 CHECK 约束保留
- [ ] `IdentityMapper.xml`、`MembershipMapper.xml`、`OperatorMapper.xml`、`TenantMapper.xml` 中所有查询，对涉及的每张表（包括 JOIN 进来的表）都加上 `NOT deleted`
- [ ] `TenantMapper.xml` 的 `updateStatus` 同时写 `updated_at = now()`；`updated_by` 按调用场景填写，没有认证上下文时为 NULL
- [ ] demo seed SQL（`R__demo_identity.sql`、`R__demo_operator.sql`）和测试 fixture 依靠列默认值仍能执行；如果其中依赖了旧约束名（比如 `ON CONFLICT ON CONSTRAINT`），一并调整
- [ ] 测试：
  - `deleted = true` 的 identity 无法登录，并且返回的响应与未知账号相同
  - `deleted = true` 的 membership 不会出现在租户列表中，也不能选择或切换
  - `deleted = true` 的 operator 无法登录 Platform Realm
  - 账号被逻辑删除后，可以再插入同名账号
  - 现有 IAM 测试全部通过
- [ ] 后端 `./mvnw verify` 通过

## Comments
