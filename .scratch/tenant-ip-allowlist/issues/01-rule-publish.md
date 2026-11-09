# 01: 规则管理、Redis 发布与全量恢复

**What to build:** 按 spec 第 2、4 节，提供规则读写接口并发布到 Redis。

**Blocked by:** —

**Status:** resolved

- [x] 校验 IPv4、IPv6、CIDR、条数上限，拒绝 `/0`，并保存规范化后的结果
- [x] 保存时必须包含当前 IP
- [x] 发布与数据库的一致性策略与现有租户状态一致，Redis 失败时不留下不一致状态
- [x] 启动时全量发布，未启用的 Tenant 写入明确的“无限制”值
- [x] 记录 `tenant.ipAllowlistUpdate`，跨租户访问被拒绝

## Comments


## Answer

已实现规则管理、同步发布与启动恢复。只在 IAM 的 `tenant` 表增加 `ip_allowlist` 列（V27，含中文注释），没有新增租户业务表。

- `GET/PUT /security-settings/ip-allowlist`（网关前缀 `/iam`）：复用 `iam:security:manage`，请求 `{rules: string[]}`，响应 `{rules: string[], currentIp: string}`；租户只取已验签 Token。
- IPv4、IPv6、CIDR 字面量规范化，最多 100 条，禁止 `/0`、hostname、zone；条目错误通过 `errors[].field=rules[index]` 定位。保存必须包含当前可信客户端 IP，空列表为无限制。
- SDK Core `TenantIpAllowlist` 提供 `parse/decode/rules/encode/allows`；无限制编码 `*`，受限值为逗号分隔的规范化规则；缺失或非法发布值不会被解释为无限制。`MembershipStateKeys.tenantIpAllowlist(UUID)` 定义 `iam:<tenantId>:ip-allowlist`。
- 更新先在事务内修改并持有 Tenant 行锁，再同步写 Redis；实际 Redis 拒写时 HTTP 失败，数据库回滚、旧发布值保留。全量发布按同一行锁读取最新值，防止恢复旧快照覆盖修改。IAM ApplicationRunner 启动执行全量发布；新 Tenant 登记或状态先到登记时立即补齐规则键。
- 成功更新记录 `tenant.ipAllowlistUpdate`；提供 `TenantIpAllowlistService.rules(UUID)`、`currentIp()`、`clear(UUID, UUID)` 与 `publishAll()` 给后续工单复用。`clear` 调用方负责 Operator 权限与清除审计；本工单没有实现 Operator 入口或 Token/网关拒绝逻辑。

验证：依照已确认的 HTTP/启动边界逐条执行 RED → GREEN；`TenantIpAllowlistTest` 24 个用例通过，包括规范化、当前 IP、条目定位、条数上限、IPv4/IPv6 CIDR 边界、跨 Tenant 权限、启动恢复、成功审计和真实 Redis 拒写回滚。

```sh
cd backend
./mvnw -o -pl forge-iam -am spotless:apply install -DskipTests
./mvnw -o -pl forge-iam spotless:apply test -Dtest=TenantIpAllowlistTest,ArchitectureTest,SchemaCommentTest,TenantProvisioningTest,TenantLifecycleTest,TenantMfaRequirementTest
./mvnw -o -pl forge-sdk/forge-sdk-core test -Dtest=ArchitectureTest
```

IAM 定向验证共 42 个测试通过，Core 架构测试 1 个通过；格式化与 `git diff --check` 通过。IAM 模块全量测试由集成阶段在后续工单合入后统一执行一次。
