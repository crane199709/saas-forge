# 工单 03 验证记录

## 范围与边界

- 仅实现 Operator 紧急清除指定 Tenant IP Allowlist 的 IAM HTTP 入口。
- 接口：`POST /platform/tenants/{tenantId}/ip-allowlist/clear`；经网关加 `/iam` 前缀。
- Platform Realm Token、当前 active Operator、既有 `platform:tenant:manage` 权限共同保护入口。
- 请求 `{reason: string}`：必填、拒绝空白、最多 500 字符（沿现有 Tenant 生命周期原因长度）。
- 清除调用工单 01 的 `TenantIpAllowlistService.clear`，继续使用同一事务和同步 Redis 发布；成功后记录 Platform Realm 的 `tenant.ipAllowlistClear`，包括目标资源、Operator 和去除首尾空白后的原因。
- 测试边界沿用户预先确认的 IAM HTTP 集成边界，Redis 使用真实临时实例；审计不连接 MQ，核对 HTTP 请求产出的待投递 Audit Event 契约。网关每次请求和 Playwright 流程由工单 02、04 及集成阶段验证。

## TDD 与已完成验证

首片 HTTP 测试先 RED：清除期望 200，未实现时返回 403。之后写入完整受控入口并 GREEN。

自动权限审核拒绝了暂时缺少权限、原因校验、审计的中间实现；该命令未执行。已采用完整安全实现作为首片最小可行实现，未落地开放入口。后续逐片补充并执行安全约束回归：

- 普通持有管理租户权限的 Operator 成功，无权限、停用和不存在的 Operator 返回 403。
- Tenant Token 与匿名请求返回 401。
- 缺少、null、空字符串、ASCII/Unicode 空白原因及 501 字符原因返回 400，500 字符原因可提交。
- 不存在和逻辑删除的 Tenant 返回 404，未为错误目标发布无限制值。
- 成功清除立即发布 `*`，目标 Tenant 读回空列表，另一个 Tenant 保持限制。
- 来源 IP 位于旧规则之外也可通过 Operator 紧急入口恢复；审计包括 Platform Realm、Operator、目标 Tenant、原因和成功结果。

审计单场景第一轮在 PostgreSQL 容器等待 ready 日志时超时，HTTP 测试未执行；原样重跑 1/1 通过，不改测试容器设施。

## 最终验证

- `./mvnw -o -pl forge-iam test -Dtest=OperatorIpAllowlistClearTest,ArchitectureTest -DforkCount=1`：11/11 通过（HTTP 10、架构 1）。
- 真 Redis 拒写时清除返回 5xx，通过 HTTP 读回旧规则、Redis 保留旧值，并未新增成功清除审计。
- 普通管理租户 Operator 与 Super Admin 均可清除；目标 Tenant 发布为 `*`，另一个 Tenant 保持限制。
- `spotless:apply` 与 `git diff --check` 通过。
- IAM 全量测试留给全部工单合入后的集成阶段统一执行。
- 已在功能提交后无冲突合入包含工单 02 的最新 integration `9b3e2fd`（本分支合并提交 `0c95b74`），保留 Platform `/platform/tenants/**` matcher 及工单 02 的 Membership 选择接口安全配置。
- 合并后重新安装当前 SDK/依赖，并单 fork 重跑 `ArchitectureTest`：1/1 通过；合并后的 IAM 模块全量由集成阶段统一执行。
