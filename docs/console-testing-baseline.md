# 前端检查与后端验证归属

正式 Console 在独立 [saas-forge-web](https://github.com/crane199709/saas-forge-web) 维护，复用完整 Soybean Admin Element Plus。所有前端命令在该仓库执行，不读取后端源码，不运行 Maven/JDK，不启动后端。

| 检查 | 当前入口与证据边界 |
| --- | --- |
| 类型、只读 Lint、Runtime/业务测试、生产构建 | `pnpm run verify`，失败立即向上传播 |
| 正式 Client、会话协议、精确数字/金额/日期 | `tests/console-client.test.ts`、`console-protocol.test.ts`、`locale-format.test.ts`，由 `pnpm run test` 执行 |
| 真实登录、上下文、刷新、多标签冒烟、退出、语言/主题、键盘/焦点/Cookie | `pnpm run verify:browser -- <handoff.json> <新产物目录>`，连接已启动的受信 HTTPS 环境 |
| Remote 冻结制品与消费 | `pnpm run build:static-remote`、`scripts/remote-browser-checks.mjs`；真实资源/CORS 证据见前端 #10 |
| TLS、精确 CORS、静态 Remote、Gateway 转发 | 后端 `scripts/test/`；Java 服务与契约保留后端 Maven 门禁 |
| Fresh、业务主链、Token/Redis 故障与完整安全矩阵 | #183–#189 与父 #201，同轮交接后分别执行后端探针与前端页面验证 |
| 完整视觉像素基线、自动无障碍、布局与生产错误边界 | #103 / #201 仍待适配；当前冒烟截图不等于完整视觉回归通过 |
| 完整 ICU/语言资源与 Session Tabs 负例 | #201 与专项责任表继续跟踪；现有格式化和会话冒烟不替代全量覆盖 |

交接格式、失败传播及未迁覆盖见[独立验证](acceptance/independent-verification.md)。完整检查由各自 CI 承担；后端 CI 不运行浏览器。没有新增永久 skip，也不把缺少环境或缺少基线标记为通过。

旧 `consoles/`、Vitest 配置及截图属于历史实现。旧视觉容器脚本只调用已迁出配置，没有独立测试断言；#207 清理失效包装入口，不删除历史基线或覆盖要求。复现旧源码见[历史追溯](acceptance/console-history-reproduction.md)，清理与待聚合清单见[#207 记录](acceptance/issue-207-cleanup.md)。
