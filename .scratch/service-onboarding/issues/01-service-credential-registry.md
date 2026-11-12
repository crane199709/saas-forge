# 01: 服务凭证落库，认证每请求查库

**What to build:** 在 IAM 增加 `service_credential` 表与认证查询路径，取代启动期复制的静态令牌 Map。凭证支持多条、`active` / `disabled` 两态、SHA-256 摘要存储、按摘要绑定服务身份；认证每次请求查库，停用对多实例请求级生效。静态 `forge.iam.manifest.tokens` 降级为引导通道：数据库命中优先，未命中再比对静态 Map 并记 WARN。生命周期写 Platform Realm 审计。见[规格](../spec.md#acceptance)与 [ADR-0023](../../../docs/adr/0023-service-credential-as-managed-state.md)。

**Blocked by:** 无

**Status:** resolved

- [x] Flyway 迁移新增 `service_credential`：`id`（UUIDv7）、`service`、`digest`、`status`、`last_used_at` 与标准列；表和全部字段写 `COMMENT ON`；`digest` 用 `WHERE NOT deleted` 的部分唯一索引；`service` 上建查询索引
- [x] 手写 SQL 只写在 mapper XML；摘要比较沿用 `MessageDigest.isEqual`
- [x] 认证路径：计算请求令牌摘要 → 单次查库命中 `active` 且未删除的记录 → 以该记录的 `service` 作为 principal；未命中再回退静态 Map 并 `log.warn`"静态凭证已弃用"
- [x] 数据库不可用时返回 503（依赖不可用）而非 401，不回退静态 Map；空令牌与格式错误保持既有 401 语义
- [x] 成功认证更新 `last_used_at`（同一事务内的单条 UPDATE）
- [x] 审计：登记、停用、轮换写 Platform Realm Audit Event（`tenant_id` 为 NULL），包含操作者、服务名、凭证指纹前 8 位；禁止记录明文与完整摘要
- [x] 认证失败审计按"服务名 + 来源"在短窗口内去重并限流，避免被刷成审计洪泛
- [x] 架构约束测试：禁止在凭证认证路径引入 `@Cacheable` 或本地缓存（参照既有 ArchUnit 规则写法）
- [x] 集成测试：同一服务两条 active 凭证均可认证；停用其中一条后该条下一次请求 401、另一条仍 200；停用全部后 401；静态 Map 回退仍可用并产生 WARN
- [x] 现有 `ServiceTokenFilterTest` 与 IAM 安全链测试全部通过；`forge.iam.manifest.tokens` 漏配不再导致启动失败（引导通道允许为空），但 DB 与静态都为空时启动仍成功、所有 internal 请求 401
- [x] 规格 §Acceptance 中"安全"全部满足；本工单不改控制台、不改 SDK

## Comments

2026-11-12：实现提交 `26ab186`、审查修复 `240eeee` 已合入集成分支。定向行为与禁缓存架构检查通过；最终独立执行 `./mvnw -o -pl forge-iam test`，314 项通过，0 失败、0 错误、0 跳过，构建成功。此前未完成的后台全量不计入验收。

## Answer

服务凭证落库、立即停用、静态引导兼容、503 故障关闭、生命周期及限流审计均完成；数据库故障日志可按 traceId 追踪且不含凭证或完整摘要。详见 [审查修复](../review-fixes.md) 与 [集成验收](../acceptance.md)。
