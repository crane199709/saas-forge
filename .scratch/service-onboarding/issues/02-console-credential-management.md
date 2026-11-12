# 02: 控制台登记、停用与轮换服务凭证

**What to build:** 平台控制台新增"服务凭证"页，管理员为一个服务登记凭证、查看现有凭证、停用与轮换。注册返回一次性明文，之后只能看到指纹与最后使用时间。platform 经 `/internal` 调 IAM，符合 [ADR-0021](../../../docs/adr/0021-operator-ops-via-service-internal-endpoints.md) 的"数据留服务、平台聚合"。见[规格](../spec.md#decisions)。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] IAM 内部端点（仅 `/internal/`，网关已拒绝）：登记（服务名 → 一次性明文）、列表（只含指纹、状态、创建时间、最后使用时间）、停用、轮换（建新 + 停旧，返回新明文）；一律返回 `ApiResponse<T>` 信封，错误用 `service-credential.<原因>` 错误码
- [ ] 服务名校验：`[a-z][a-z0-9-]*`、长度与 `resource_manifest.service` 对齐；登记不要求该服务已有 Resource Manifest，但响应里给出"是否已上报"提示
- [ ] 明文只在登记与轮换的响应体中出现一次：不落库、不写日志、不进审计、不可再次查询
- [ ] platform 侧新增 Operator 权限码 `platform:service-credential:manage`（写入 platform 的 Resource Manifest）与聚合接口；调用 IAM 用服务发现 `lb://`，不可达时按服务标记不可用而非整体失败
- [ ] 控制台页面：列表（服务名、状态、指纹、最后使用时间）、登记对话框（显示一次明文与可复制的 `FORGE_MANIFEST_TOKEN=...` 片段）、停用确认、轮换引导（提示"先部署新凭证，再停旧"）
- [ ] 页面按 `docs/standards/sub-app.md` 与 `docs/agents/frontend-design.md` 三步完成，走查需附截图
- [ ] 后端测试：权限缺失 403、服务名非法 400、停用后不在 `active` 列表、轮换后旧凭证失效；IAM 不可达时页面显示不可用状态
- [ ] 浏览器验证：登记 → 复制片段 → 停用 → 列表状态变化；明文刷新后不可再取

## Comments

（暂无）
