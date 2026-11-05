# 25: 主链路 E2E 验收

**What to build:** 用 Playwright 自动验证第一期主链路：一个拥有两个租户 Membership 的用户登录 → 选择租户 → tenant-app 动态加载 demo 子应用 → 按权限显示或隐藏按钮、按数据范围显示订单 → 切换租户后菜单、按钮与数据随之变化；以及停用成员后其会话立即失效。作为规格第 15 节验收标准的端到端证明。

**Blocked by:** 07 Token 即时失效与刷新, 12 资源清单与功能权限校验, 18 数据权限端到端（Data Scope 预设）, 21 应用注册表与子应用动态加载

**Status:** resolved

- [x] E2E 环境可由脚本一键准备（依赖、服务、种子数据）
- [x] 场景：登录 → 选择租户 → 加载子应用 → 权限按钮显示/隐藏 → 订单按数据范围展示
- [x] 场景：切换租户后菜单、按钮、订单数据随之变化
- [x] 场景：管理员停用该成员后，其下一次操作被登出
- [x] 场景：Tenant Realm 会话无法访问 platform-console 的接口
- [x] E2E 可在本地与 CI 中运行


## Comments

2026-10-30 实现：
- `deploy/e2e/run.sh` 一键安装依赖、构建并串行启动 IAM、demo-service、platform、gateway，创建独立 PostgreSQL/Redis 容器并写入双租户种子；固定专用本机端口，退出清理本次环境，不复用开发数据。Java 子进程不继承开发环境的 Spring 配置。
- `frontend/e2e/main-flow/main-flow.spec.ts` 使用真实登录、授权码 + PKCE、网关和 Module Federation，无接口 mock 或会话注入。覆盖 A 本人订单/B 全部订单、切换后的审计菜单与创建按钮、未授权创建 403、管理员停用后下一次操作登出及旧 Token 重放 401、Tenant Token 对两类平台路由返回 401。
- demo 子应用补齐订单列表、分页和刷新操作，通过共享 SDK 请求真实数据；原有 mock 浏览器回归增加订单接口 fixture。
- `.github/workflows/main-flow-e2e.yml` 提供 PR/main push/手动运行及失败报告上传；运行说明见 `docs/main-flow-e2e.md`。
- 验证：真实 E2E 3/3，原有 Playwright 15/15，前端单元测试 117/117（tenant-app 92、platform-console 21、脚手架 4），全工作区类型检查、改动文件格式检查、前端构建和后端全量 `./mvnw -B verify`（含 Spotless、Testcontainers）通过。先确认新增场景在缺失订单列表处失败，再补齐实现并复验通过。
- 审查：按 code-review 技能串行完成 Standards 与 Spec 两轴审查，均无可行动问题。
- 边界：MQ 投递和 Nacos 发现关闭，注册表副本通过种子准备；demo Resource Manifest 真实上报，认证、即时失效、权限和 RLS 保持开启。本工单不证明 MQ/Nacos/开通/对象存储链路；远程 CI 尚未触发，已验证相同脚本本地运行。
