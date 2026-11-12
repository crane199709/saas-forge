# 服务凭证改为可管理的状态化凭证，认证每请求查库

IAM 不再把 manifest 上报令牌当作启动期读入的静态配置：每个 Business Service 可以有多条 Service Credential，库里只存 SHA-256 摘要，凭证按摘要绑定服务身份，状态只有 `active` / `disabled`。认证路径每次请求按摘要查一次库，不引入任何缓存，因此登记与停用在多个 IAM 实例上都是请求级生效；停用立即失效，不设宽限期，轮换靠"建新 → 服务部署 → 停旧"。`forge.iam.manifest.tokens` 保留为已弃用的引导通道（IAM 自身与本地/E2E），数据库命中优先，静态命中记 WARN，迁移完成后删除。生命周期写 Platform Realm 审计（`tenant_id` 为 NULL，见 [V18__audit_event.sql](../../backend/forge-iam/src/main/resources/db/migration/V18__audit_event.sql#L30)）。

选择查库而不是缓存，是因为凭证变更本来极低频（每次接入一个服务才发生），而 manifest 上报只在服务启动时发生一次，IAM 这条路径的请求量很低；用一致性换这点 QPS 不划算。摘要用 SHA-256 而不是 bcrypt/Argon2：凭证是 256 位随机串，不需要慢哈希，且慢哈希无法按摘要建唯一索引、只能全表逐条比对。

## Considered Options

- 保留静态 Map，靠 Nacos 配置刷新热更新：`ServiceTokenFilter` 在安全链构建时复制 Map，配置刷新不会重建过滤器；即便重建，也只是把 YAML 换成另一份进程内快照，仍做不到只停用一条、也留不下审计。
- 查库 + 短 TTL 缓存或版本号：能省掉一次查询，但要么在停用后留下一个最长等于 TTL 的有效窗口，要么再引入一套版本失效机制；与"泄漏后立刻吊销"的诉求冲突。
- 对称加密存凭证原文以便"再次查看"：需要额外密钥管理，且备份泄漏时全量凭证同时暴露；明文只在创建/轮换时显示一次已够用。
- 给服务签发短期 JWT 替代长期凭证：[ADR-0021](0021-operator-ops-via-service-internal-endpoints.md) 已在运维令牌上权衡过，结论是不值得为它新增签发与密钥管理。

## Consequences

- 认证路径对 IAM 数据库有强依赖：库不可用时返回 503 而不是 401，不静默回退静态 Map。
- 轮换不再是改配置，而是一次管理操作，需要控制台入口与 Operator 权限码 `platform:service-credential:manage`。
- 服务侧仍要把凭证写进 `forge.manifest.token`；这次改动消除的是"IAM 改配置 + 重启"，不是"服务持有秘密"。
- 现有 iam / platform / demo / lowcode 四个服务需要一次性迁移到数据库凭证，之后才能删除静态 Map。
