# 主链路 E2E

工单 [25](../.scratch/saas-forge-mvp/issues/25-main-flow-e2e.md)。真实浏览器连接 tenant-app、动态加载 demo 子应用，经网关访问 IAM、demo-service 和 platform；不 mock 接口、不注入 Token。

## 一键运行

准备 Docker、JDK 17、Node 24 和 pnpm 12.4.2，在仓库根目录执行：

```bash
deploy/e2e/run.sh
# 只运行一个场景
deploy/e2e/run.sh --grep '双 Membership'
```

Linux 首次运行还需安装 Chromium 系统依赖：在 `frontend` 执行 `pnpm install --frozen-lockfile && pnpm exec playwright install --with-deps chromium`。CI 工作流已包含此步骤。

脚本安装前端依赖和 Chromium、串行构建前后端、创建独立 PostgreSQL/Redis 容器、等待四个服务就绪、写入种子并运行测试。占用端口会直接失败，不复用已运行服务。固定本机端口：数据库 `15432`、Redis `16379`、网关 `18000`、IAM `18101`、platform `18102`、demo `18201`、Shell `15174`、remote `15175`。

数据库目标是**本次 Compose project 的 postgres 容器中的 forge 数据库**；无宿主数据目录挂载，不接受开发库连接参数。测试种子只通过该容器的 `psql` 执行。退出时停止脚本创建的进程并删除本次容器和卷；不操作开发环境。日志和临时签名密钥留在被 Git 忽略的 `deploy/e2e/run-*`，不要上传密钥。HTML 报告和失败 trace 位于 `frontend/playwright-report`、`frontend/test-results`；trace 含测试会话信息，仅限测试环境留存。

## 数据与断言

测试账号 `e2e-member` 有两个 Membership，`e2e-admin` 是 A 租户管理员；密码均为仅供隔离测试的 `Demo-password-123!`。

| Active Tenant | 菜单和按钮 | Data Scope | 可见订单 |
| --- | --- | --- | --- |
| A | 审计菜单、新建订单按钮可见 | 本人 | A 本人订单 |
| B | 审计菜单、新建订单按钮隐藏 | 全部 | B 本人订单、B 他人订单 |

A 他人订单作为同租户排除样本，A/B 订单互为跨租户排除样本。测试比较完整列表，额外验证 B 的创建接口返回 403。管理员在另一个浏览器上下文登录，通过公开成员停用接口操作；成员下一次刷新订单收到 401、回到登录表单并清除本地会话，旧 Token 重放仍为 401。最后通过管理员接口恢复成员，便于重复运行。

Realm 场景先证明 Tenant Token 可访问订单，再检查 `/platform/operators/current` 和 `/iam/platform/roles` 均拒绝该 Token。

## 边界

数据库准备与新服务接入步骤见[平台侧接入清单](service-onboarding-checklist.md)。E2E 与本地 compose 共用 `deploy/postgres/init/lib/provision-service.sql`，由初始化入口分别传入 iam/platform/demo 的本地凭证。

MQ 投递和 Nacos 发现关闭，网关用固定地址路由；注册表副本由种子准备，demo 的 Resource Manifest 仍由服务启动时向 IAM 真实上报。认证、Redis 即时失效、功能权限、数据权限、租户拦截器与 PostgreSQL RLS 保持开启。本用例不证明 MQ 广播、租户开通、对象存储或服务发现，这些由相应集成测试负责。

原有 `frontend/pnpm test:e2e` 保留为 mock 浏览器回归，与本套真实 E2E 分开运行。GitHub Actions 的 `Main flow E2E` 工作流在 PR、main push 和手动触发时运行，失败报告及服务日志保留 7 天。
