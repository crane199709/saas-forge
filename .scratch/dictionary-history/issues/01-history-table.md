# 01: 字典历史表与版本记录

**What to build:** 按 [spec](../spec.md) 的“实现决策”，新增字典变更历史表，并在有效变更后追加版本快照。

**Blocked by:** —

**Status:** resolved

- [x] 历史表包含租户、服务、字典、版本号、完整覆盖快照、变更摘要、变更人与时间；带标准列与租户隔离
- [x] 版本号按“租户 + 服务 + 字典”递增；快照是当时的租户覆盖集合
- [x] 在新增、改名、改排序、停用、启用的有效变更后追加；无变化不追加
- [x] Manifest 内置项不进入快照
- [x] 接入租户清理
- [x] 测试覆盖：有效变更、无变化、多次变更版本递增、跨租户隔离、清理

## Answer

- 新增 IAM `V32__dictionary_history.sql`：完整租户覆盖快照、摘要、版本、创建人/时间与全部标准列；所有表列中文注释，启用并强制 RLS，唯一版本使用未删除行的部分索引。
- 新增 `DictionaryHistoryService.list(tenantId, service, dictionary)`，按版本倒序返回历史与只读覆盖项；调用方须绑定可信 `CurrentTenant`，公开权限与 HTTP 查询由 02 完成。
- 新增、改名、排序、停用、启用在既有字典事务锁内追加快照；字典修改与历史同事务提交。快照只读取租户覆盖行，包含存量与停用覆盖，不包含 Manifest 默认项。
- 生效显示名/排序或启用状态未变化时直接返回；不写覆盖、不追加历史，也不产生审计和 Redis 缓存版本噪声。原有效变更审计仍独立记录。
- 租户清理器逻辑删除该租户历史，重复清理幂等且不影响其他租户。
- 实现提交：`38d1a0fb`（`feat(reference-data): 记录字典覆盖变更历史快照`）。

## Comments

- 2026-11-13：按已授权接缝使用 IAM 字典管理 HTTP 写入、公开 history service 读取，完成新增、更新/排序/启停、无变化、租户清理四个 red → green 切片；另验证跨租户 RLS、无上下文读取、连续并发版本与完整快照、存量覆盖首次留存。
- 验证：先执行 `./mvnw -o -pl forge-iam -am install -DskipTests`，再运行 `./mvnw -o -pl forge-iam spotless:apply test -Dtest=DictionaryHistoryTest,ReferenceDataTest,SchemaCommentTest,ArchitectureTest`：22 项全部通过（7 + 12 + 2 + 1）；独立 `spotless:check` 与 `git diff --check` 通过。
- 查询 HTTP、diff、独立 Permission Code 与前端由 02 继续；模块全量由集成负责人交付前运行。本票没有操作运行中的业务数据库。
