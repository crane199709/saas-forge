# 两仓独立验证与环境交接（#206）

后端只维护 Maven/JDK 17、契约、迁移、服务及环境探针；前端维护统一 Console、固定版本 Client、类型、Lint、Runtime、国际化、视觉和 Chrome 页面检查。两仓无需相邻，不互相读取源码，不共同发布。父 #201 与 #183–#189 保持原状态。

## 日常检查

- 后端：`node --test --test-concurrency=1 scripts/test/*.test.mjs`；Java/契约改动执行受影响模块 Maven 检查。完整 `./mvnw verify` 与 Fresh Tenant/Nacos 验证仍在 `.github/workflows/`，没有浏览器步骤。
- 前端：在独立仓库运行 `pnpm run verify`（类型、只读 Lint、全部 Node 测试、构建）。`pnpm run verify:browser -- <handoff.json> <新产物目录>` 连接已有 Console/Gateway。它不启动前端或后端，也不执行 Docker、Maven、数据准备、替换或清理。
- 日常启动仍是前端 `pnpm run dev`、后端 IDE Run/Debug。完整 Compose 仅用于专项环境准备。

## 普通环境交接

先由环境维护者提供受信 HTTPS Console、Gateway 与已构建/启动的前端，然后在后端仓库执行：

```sh
node scripts/acceptance-handoff.mjs attach "$SF_ROUND_DIR" "$SF_CONSOLE_ORIGIN" "$SF_API_ORIGIN"
node scripts/acceptance-handoff.mjs probe "$SF_ROUND_DIR/handoff.json" "$SF_ROUND_DIR/backend.json"
```

目录必须尚不存在，父目录须已存在；可放在 Git 忽略的 `.scratch/` 或外部受限目录。地址必须是无路径、查询、凭据的完整 HTTPS Origin。`attach` 只读 Gateway 公钥，生成 UUID 轮次、24 小时有效期、代码基线/dirty、隔离类别及清理责任。`existing-environment` **不证明 Fresh**；`source-checkout-only-runtime-unverified` 明确表示 Git 工作区版本不是运行制品的来源证明。

后端 `probe` 重新检查同轮公钥摘要、允许的精确 Console Origin 和不可信 Origin 的 403/无允许头。它不做业务状态准备，也不证明登录、Token 撤销、Redis 故障或 Remote 隔离；这些仍由对应专项承担。

## Fresh 准备：先保留轮次，再由环境方启动

```sh
node scripts/acceptance-handoff.mjs prepare "$SF_ROUND_DIR" "$SF_CONSOLE_ORIGIN" "$SF_API_ORIGIN"
```

这一步不启动任何应用，只创建 `preparation.json` 和本轮 Compose overlay。overlay 使用唯一项目/镜像名，所有基础设施及内部服务不暴露端口；Gateway 只使用随机回环端口。Kafka 镜像的两个临时目录使用 tmpfs，避免隐式创建无项目归属的匿名卷，数据仍落独立 kafka-data 卷。此生成文件是本轮产物，不是可提交的个人配置模板。

环境准备方自行维护 Git 忽略且权限受限的 `$SF_ACCEPTANCE_ENV`，采用专用 Secret 目录、新签名私钥和引导账号，不能复用开发者环境文件或数据卷。非敏感配置与 Secret 规则不变。先记录轮次，再准备环境：

```sh
SF_PROJECT=$(node -p 'JSON.parse(require("fs").readFileSync(process.argv[1])).isolation.project' "$SF_ROUND_DIR/preparation.json")
SF_COMPOSE_DIR="$PWD/deploy/acceptance"
SF_OVERRIDE="$SF_ROUND_DIR/compose.override.yaml"
# 上述路径使用绝对路径；所有 Compose 操作均显式指定项目和文件。
compose_round() {
  docker compose --project-directory "$SF_COMPOSE_DIR" --env-file "$SF_ACCEPTANCE_ENV" \
    --project-name "$SF_PROJECT" --file "$SF_COMPOSE_DIR/compose.yaml" --file "$SF_OVERRIDE" "$@"
}
compose_round build
COMPOSE_PROJECT_NAME="$SF_PROJECT" LOCAL_COMPOSE_ENV_FILE="$SF_ACCEPTANCE_ENV" \
  LOCAL_COMPOSE_OVERRIDE_FILE="$SF_OVERRIDE" bash scripts/initialize-local-iam-signing-key.sh
compose_round --profile bootstrap run --rm --pull never iam-platform-admin-bootstrap
compose_round --profile service-client-bootstrap run --rm --pull never iam-reserved-service-client-bootstrap
compose_round up --detach --no-build gateway iam-service tenant-access-service entitlement-service audit-service
compose_round port gateway 8080
```

前述命令只供已获授权的环境准备方执行，不由前端调用。先检查每一步退出码，失败就停止。准备方配置独立受信 HTTPS Edge，使 Console 指向本轮前端、API 指向上述随机 Gateway；不要接管开发者现有 443/进程，不自动修改系统信任。#183–#189 还需要 Remote、攻击来源、Mailpit 和测试 Receiver 等各自 overlay，按下面责任表添加，不由基础交接伪造这些能力。业务资源通过真实页面建立，首次改密也由正式页面完成。

统一 Console 的受控开关默认关闭。准备方还须按 [#204 切换说明](issue-204-unified-console.md#受控切换)，在专用环境所有参与实例上统一启用 `security.browser.console-enabled` 并受控重建；默认 Compose 启动成功不代表 v2 会话入口可用。本地专项可在 Git 忽略、受限的本轮部署覆盖中明确该决定，不把个人配置模板提交到仓库，不修改共享环境或动态刷新安全开关。新建空环境没有旧会话；复用数据的环境必须另行完成切换授权及旧协议退役步骤。

环境 Ready 后运行：

```sh
node scripts/acceptance-handoff.mjs ready "$SF_ROUND_DIR"
node scripts/acceptance-handoff.mjs probe "$SF_ROUND_DIR/handoff.json" "$SF_ROUND_DIR/backend.json"
```

`ready` 拒绝旧容器/卷/网络、跨项目网络与卷、缺少实际数据卷挂载、缺少五服务、错误镜像版本标签或非 JDK 17。记录具体容器/镜像 ID、网络、卷和源版本；HTTPS 与本轮回环 Gateway 公钥摘要必须相同。版本标签来自本轮构建输入，不是独立签名供应链证明；构建期间不要改变源码。Ready 不代表后续业务或安全场景已通过。

## 证据、失败与清理

把非敏感 `handoff.json` 交给前端；邮箱/密码仅通过独立 0600 文件传递，不能放入交接 JSON、命令参数值、日志、截图、trace 或 HAR。前端记录 Chrome、前端 commit/dirty、lock 摘要、正式 Client 版本/来源、每阶段脱敏结果。当前入口验证平台登录与上下文选择、刷新、第二标签页、退出、双语/主题截图、键盘/标题焦点、Cookie 属性及错误传播。

```sh
node scripts/acceptance-handoff.mjs correlate "$SF_ROUND_DIR/handoff.json" \
  "$SF_ROUND_DIR/backend.json" "$SF_BROWSER_RESULT/browser.json" "$SF_ROUND_DIR/result.json"
```

只有相同 runId、相同交接文件 SHA-256、有效时间窗口、两类成功且无跳过/失败的非空检查才能关联通过。摘要保留两份原始证据哈希，作用域固定为 `handoff-smoke-only`。不能用这个通过状态关闭完整业务/安全票；后续切片继续输出自身直接证据。失败退出非零；无原始异常/凭据输出，不自动重试登录，不吞掉结果。已有文件不能被覆盖，重跑选择新输出目录。

准备方始终负责清理。前端只关闭自己创建的 Chrome。失败时保留环境及受限诊断供定位；保留正式脱敏证据后，经确认仅对本轮项目执行 `compose_round down --volumes`，不要对默认开发项目执行，不删除共享镜像/Secret。工具本身不自动销毁任何资源。需要长期保留的证据移入 `docs/acceptance/`；`.scratch/` 不构成交付证据。

## 保留的覆盖与责任

| 来源 | 后端/环境责任 | 前端责任与待迁范围 |
| --- | --- | --- |
| #183 聚合规格 | Fresh 隔离、同轮身份、JDK 17、真实依赖、探针 | 统一 Console + Chrome，API/Remote/攻击来源独有覆盖保留；历史双 Console 不照搬 |
| #184 主链/Audit | 一次引导、真实邮件、同轮 Audit 查询关联 | 中文权益→Tenant→Subscription→初始化→密码设置→Tenant 登录/选择/切换；各业务票局部证据不能替代同轮主链 |
| #185 安全/Redis | 隔离环境 Token/权限攻击与 Redis 故障、恢复探针 | 正式页面失败关闭与恢复、多标签/晚到响应；不在普通共享环境注入 |
| #186 生命周期/英文 | 服务拒绝与会话撤销事实 | 页面冻结/解除冻结、重新登录、英文代表流程；旧会话不得复活 |
| #187 OAuth 消费 | 非生产 Receiver、服务发现/Gateway、真实 Scope/窗口/吊销探针 | 创建/轮换/吊销与一次 Secret 展示；已迁页面不等于 24 小时窗口和完整拓扑通过 |
| #188 未知签发 | 已提交与被替代凭据失效的服务侧证明 | Chrome 丢响应、原操作者恢复、禁止重复新请求、Secret 不入证据 |
| #189 同轮汇总 | 全新环境/清理、故障恢复、后端版本与 CI | 同轮资源顺序、按场景归属错误、四项全覆盖；当前冒烟关联器不替代聚合执行器 |
| #103 布局 | 无新增后端能力 | Soybean/Element Plus 现有布局优先；不恢复旧自建 Design System/双 Console。保留主辅内容顺序、320 CSS px 重排、表格局部滚动、焦点/键盘/命名及真实路由/Remote 消费要求，仍待该票按新底座实施 |

适用入口已迁入：前端 `tests/console-session.test.ts` / `console-client.test.ts`（Runtime/正式 Client）、`console-protocol.test.ts`（原调用方会话门禁）、`locale-format.test.ts`（原精确格式化）、`scripts/verify-browser.mjs`（真实登录/展示/焦点/Cookie）、`scripts/remote-browser-checks.mjs`（Remote）。业务状态测试保留在前端 `tests/`。后端 `scripts/test/` 的 TLS/精确 CORS/Remote/Edge 检查及 Java 服务测试不迁到前端。

尚未全量迁入的独有覆盖：旧 `consoles/integration-test/` 的完整 Session Tabs/错误与撤销 Token/Redis/Fresh 业务聚合；旧 `browser-test/` 的完整布局/视觉基线、自动无障碍矩阵、生产错误边界；旧 i18n 的 ICU/资源完整性覆盖按 Vue i18n 适配。它们保留在 Git 历史和迁出清单中，由 #183–#189、#103、#201 及相应业务票继续承担，不因新冒烟入口通过而删除或勾选。旧 `scripts/run-console-visual-container.sh` 仅是历史载体，不是当前可运行入口，也未用于后端 CI。
