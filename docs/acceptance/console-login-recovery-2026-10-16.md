# 本机统一 Console 登录恢复（2026-10-16）

范围：本机开发环境、JDK 17、`console.saas.forge.test` / `api.saas.forge.test`。本记录不代表完整 CI 或其他环境验收。

## 原因与处理

1. IAM 数据库只有 V1–V24；当前代码的撤销索引恢复读取 `iam_revocation_fences.identity_id`，该列由 V26 添加，因此 IAM 启动失败。Nacos dev 中没有健康 IAM 实例，真实 bootstrap 返回 `503 UPSTREAM_UNAVAILABLE`。
2. Gateway 将上游异常转换为 Problem Details 时调用响应 `reset()`，连同 CORS 响应头一起清除，导致浏览器只能报告 CORS 失败。修复为保留此前已完成的 CORS 决策头，其余上游响应头和正文仍清除；未放宽来源、凭据、Cookie 或 CSRF 规则。
3. 前端待退出意图持久化后，无法完成 bootstrap/退出便继续隐藏登录表单。增加中英文认证服务不可用提示，并明确待退出仍未完成；不删除退出意图、不绕过服务端撤销。
4. IAM 新建的 IDE 配置缺少 `-Dsecurity.browser.console-enabled=true`。IAM 健康后，v2 路径仍返回 404，被 Gateway 规范化为 502。数据库 `legacy_blocked=true`，Gateway、Tenant Access、Entitlement 的 IDE 配置已有该参数；需要恢复 IAM 的既有统一协议模式。未修改 Nacos 或新建配置模板。

## 数据库操作

- 对照当前 SQL 与实际 Flyway 历史：V1–V24 校验和全部一致，无历史漂移，不执行 repair。
- 迁移前生成受限 PostgreSQL custom-format 备份，`pg_restore --list` 成功读取归档；未演练完整还原。
- 用户明确批准后，通过现有 `iam-migrate` 独立任务执行 V25、V26、V28、V29、V30。Flyway 校验 29 个迁移并成功应用 5 个待执行迁移，当前版本 V30。版本间隔 V27 为仓库现状，不重新编号。
- SQL 复核：五个新增历史记录全部成功，`identity_id` 存在；V29 为 1 个 IAM 保留客户端增加 `tenant-access:invitation:accept` scope。未修改既有迁移、重建数据库或重置账号。
- 应用继续由开发者 IDE 原生启动，迁移任务没有启动应用容器。

## 验证

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| Gateway 红灯测试 | 按预期失败 | 真实 HTTP 503 缺失允许来源头 |
| `GatewayJwksRouteTest` | 23 项通过 | 503 可读取、凭据头、非受控来源仍拒绝、既有路由规则 |
| `GatewayProblemDetailsTest` | 5 项通过 | 502/504 保留 CORS；公开发现接口不增加凭据许可 |
| 前端中英文新增浏览器用例 | 修复前 2 项失败，修复后 2 项通过 | 模拟 HTTP：服务不可用、退出失败、刷新保留意图、恢复后原操作键退出并显示登录表单 |
| 前端 Runtime / Client | 28 项通过 | 既有会话与类型化 HTTP 行为 |
| 前端 ESLint / 类型检查 / 国际化 / 隔离 UI 构建 | 通过 | 国际化 1057 个键；构建与类型检查顺序执行，避免生成组件声明期间的临时缺失 |
| 真实 Chrome 故障状态 | 通过 | Gateway 重启后真实 502 含受控 CORS 头，页面显示对应服务不可用及退出未完成提示 |
| 真实 Chrome 恢复状态 | 通过 | IAM 带统一协议参数重启后，HTTPS bootstrap 200、登录表单可见；独立匿名 Slot 的待退出意图经真实 logout 204 清除，重新显示登录表单 |

验证命令：

```bash
./mvnw -pl gateway -am -Dtest=GatewayJwksRouteTest,GatewayProblemDetailsTest -Dsurefire.failIfNoSpecifiedTests=false test
# 在 saas-forge-web 中：
node --import tsx --test tests/console-session.test.ts tests/console-client.test.ts
node --import tsx scripts/check-locales.ts
node scripts/build-ui.mjs
node node_modules/@playwright/test/cli.js test -g 'authentication outage and pending logout recover'
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit --skipLibCheck
```

未执行：完整 Compose/fresh 验收、完整 CI、真实账号密码登录、完整业务跨服务验证。独立浏览器上下文未读取或清理用户现有 Chrome 会话。
