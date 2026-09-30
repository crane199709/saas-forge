# Issue #213：Project 分页与并发修改验收

日期：2026-09-30。范围：[Issue #213](https://github.com/crane199709/saas-forge/issues/213)，父需求 [#211](https://github.com/crane199709/saas-forge/issues/211)，复用已交付的 [#212](issue-212-acceptance.md)。实现、测试和本记录随同一提交交付。

## 交付内容

[正式契约](../../examples/project-service/openapi.yaml) 新增 `GET /api/v1/projects` 和 `PUT /api/v1/projects/{projectId}`。列表以 UUIDv7 ID 升序排列，默认 50、最大 100；游标绑定集合、Tenant、页大小和可见末条 ID。末页返回 `nextCursor=null`、`hasMore=false`。修改以单个强 `If-Match` 携带正 int64 版本，原子比较并更新名称、描述、版本与更新时间。省略或 null 描述均清空；名称允许重复。

重放检查先于版本比较。成功、不可见资源 404 和版本冲突 409 的正文与状态同事务保存，重试不再执行更新。修改指纹增加方法、规范资源路径和 If-Match 版本，创建指纹保持原格式，调用方跨 Tenant/操作键空间保持不变。

新增 V5，仅授权运行账号 UPDATE name/description/version/updated_at 四列；不改变 RLS 策略，不授予修改 Tenant、ID 或创建时间的权限，不改写 V1–V4。应用仍不持有迁移凭据。没有 Task、Gateway、Remote、浏览器或通用分页框架改动。

## 验收映射

主证据为 [ProjectHttpIT](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectHttpIT.java)，正式契约由 [ProjectContractTest](../../examples/project-service/src/test/java/io/saas/forge/example/ProjectContractTest.java) 验证。

| #213 验收项 | 结果及证据 |
| --- | --- |
| 正式列表/修改契约、页大小、终页、稳定排序 | 通过：契约解析及路由一致性；HTTP 验证默认 50、最大 100、空集合、完整末页和不足一页末页、固定 ID 升序。 |
| 分页隔离、非法/不匹配游标、无重复遗漏及边界 | 通过：专用 Tenant 的 5 条数据以 limit 2 遍历，无重复遗漏；同一 Identity 切换 Tenant 后游标失败；页大小变化、错误集合、不可见/不存在锚点、空/非法游标、非法/重复/未知查询参数均 400。 |
| 名称/描述、重复名称、只读字段 | 通过：真实 HTTP 修改名称、设置与清空描述、同名修改、Unicode 字段边界；只读字段和 Tenant 别名被拒绝，ID/createdAt 保留，版本和 updatedAt 由服务维护。 |
| 原子版本竞争及恢复 | 通过：两个不同 Identity、不同幂等键、两条数据库连接的真实 HTTP 更新，同时到达数据库资源锁；释放后恰好一个 200、一个 RESOURCE_VERSION_CONFLICT，资源仅提升一次版本，重新读取后新请求可恢复修改。 |
| 跨 Tenant 列表、详情、修改与重放 | 通过：他 Tenant 的 Project 不出现在列表，详情与新键修改均 404，数据不变；切换 Tenant 后旧成功键不能重放，游标也不能绕过隔离。 |
| 修改重试与跨操作键冲突 | 通过：首次更新提升版本后，同键旧版本重试重放原成功；后续更新后仍重放旧成功与旧冲突；创建/修改双向复用键、同键改变版本均 IDEMPOTENCY_KEY_REUSED。 |
| HTTP、契约、数据库集成及既有安全门禁 | 定向检查通过：复用真实 Starter、签名 Token、公钥与 Redis 撤销验证、正式 PostgreSQL 18 迁移和受限账号；既有创建/详情身份、RLS、连接复用、幂等安全测试一并执行。直接数据库补充 UPDATE 列权限、空/非法上下文与无租户 WHERE 更新隔离；更新基础设施失败回滚、键释放与无上下文连接检查通过。 |

## 执行结果

- 分页红灯：公开 GET 集合返回 405；修复后通过。
- 初次分页实现失败：动态 MyBatis 查询缺少 UUID 类型映射，返回 503；明确参数映射后通过。
- 修改红灯：未授权更新时 HTTP 返回 503；添加 V5 列级授权后通过。
- Example 定向完整检查：18 个 HTTP/数据库集成测试与 1 个契约测试，0 失败、错误或跳过。
- 受影响模块与 Reactor 依赖的完整 `verify`：86 个测试，0 失败、错误或跳过，约 33 秒。包含 Starter 27 个单测与 4 个 Redis HTTP 集成回归。
- 双轴审查：Standards 提出 1 项非阻塞职责建议，已将稳定错误正文构造移到既有 ProjectProblem，消除 Service 对 Web Handler 的依赖；复审 Standards 0 项未解决发现；Spec 0 项发现。审查修正后再次执行 Example 完整定向检查，19 个测试均通过。

定向验证命令（HTTP 测试由 Surefire 显式选择）：

```sh
./mvnw -pl examples/project-service -am test \
  -Dtest=ProjectContractTest,ProjectHttpIT -Dsurefire.failIfNoSpecifiedTests=false -DskipITs
```

受影响模块与依赖的完整套件：

```sh
./mvnw -pl examples/project-service -am verify
```

## 证据范围与限制

认证依赖为受控 JWKS/Simple Discovery 测试设施；实际运行 Starter 签名、声明和撤销验证，PostgreSQL/Redis 与运行角色权限均真实。没有手工注入 TenantContext，也没有 Controller Mock 替代主验收。分页不承诺并发变动下的跨页快照。

本次完整套件指 Example 及其 Reactor 依赖；未执行整个仓库全部服务与质量门禁、Gateway/Nacos 部署联调、Chrome、Fresh Compose 或生产验证。不据此勾选父 PRD 的 Task/删除或 MVP 阶段 3。临时日志不作为长期证据；版本化测试、正式迁移和此记录可重现上述结论。
