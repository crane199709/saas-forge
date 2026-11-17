# 工单 06：Lowcode 实际候选升级与隔离验收

日期：2026-11-17。工单：[06](issues/06-lowcode-upgrade.md)。状态：本票全部验收通过；不代表整体发布就绪。本票使用实际工程基线，不将 Lowcode 归作已发布 `0.1.0` 工程。

## 基线与改动范围

| 项目 | 记录 |
| --- | --- |
| Lowcode 基线 | `29dea28aa391078df27111416d4e2d3436367124`，原 checkout 分支 `main` |
| Lowcode 升级提交 | `cb974b9d40247596dbd9331f56f482eef58cefb7`（`build(platform): 对齐固定候选并完成存量升级验收`） |
| Lowcode 升级分支 | `codex/v020-upgrade-lowcode`，独立附加工作树 `/private/tmp/v020-upgrade/lowcode-worktree` |
| 平台实施基线 | 集成 `326ff1b625a849eda427ccfc6e96c79cd2d1ec4c`，分支 `codex/v020-upgrade-06` |
| 原使用版本 | 后端父 POM 与前端 SDK/UI/kit 均为 `0.2.0-SNAPSHOT`；前端使用已提交 `.forge-packages` tarball/overrides，后端 CI 固定源码来源 `3f5ea01d10e18a907914ff82a9f71b3082fe05bf` |
| 版本语义 | 业务服务 `1.0.0-SNAPSHOT`、Sub-App `0.1.0`、Manifest `2` 保留；未机械递增 |
| 数据基线 | 历史 V1—V11 原文保留；隔离库实际应用至 V11 后准备代表性已有大屏、已发布快照和真实 S3 图片，再应用新增 V12。未读取或迁移共享库的 Flyway 历史 |

实施开始核对了原 checkout：`.env.example`、`application.yml` 有未提交改动，另有未跟踪 `SchedulingConfigTest.java`。只记录其 SHA-256 和工作区状态，没有搬运或提交这些改动；升级分支的 scheduling 配置经候选现有 HTTP 测试启动失败后独立实现。结束前再次核对三个文件摘要与 `git status`，已确认与开始一致，见 `preservation-final.json`。

两个跨仓库 `create_worktree` 调用没有返回 operationId，确认未创建任何结果后终止客户端请求，由主代理使用 Git fallback 创建并通过 app `attach_worktree` 成功附加。该工具边界没有改变业务基线或原 checkout。

## 候选身份与消费

候选源码：`54115992e00691be57b912e46593709c77c344a1`；版本 `0.2.0-SNAPSHOT`；目录 `/private/tmp/v020-upgrade/candidate-54115992/`。完整 `candidate.json` SHA-256：`999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`，本票重新校验通过。

| 实际前端候选包 | SHA-256 |
| --- | --- |
| SDK | `db3b4496a9691ca718b19e7dc95a9055ff242b63b6622c35a52a2cf024cee890` |
| UI | `bf5f7c012df8e86e1528b2b0435f9a8a4ad933bea9c13f280d8fdbba58bc653c` |
| sub-app-kit | `a9c964e4aad3e18fb288051e823f7715ef894f51f5e1f4ed882a39a45863cc3d` |

前端保留既有可复现的 tarball 安装模式，只将三包替换为上述实际候选并更新 lockfile 完整性值与 UI 图标依赖；不是指向已退出的临时 registry。`npm-installed-content.json` 另逐文件核对 node_modules 与 tarball，SDK 9/UI 52/kit 16 个文件全部逐字节一致。共享依赖的精确版本原已匹配候选，不额外升级。

后端使用 `/private/tmp/v020-upgrade/06-lowcode/settings.xml` 指向候选 file Maven 仓库，独立缓存 `m2/` 只预复制第三方依赖并排除全部 `io/saasforge` 与 `.lastUpdated`。解析出的 28 个 Forge JAR 文件（含时间戳与 SNAPSHOT 名称副本）摘要均属于原候选，见 `maven-consumption.json`；没有先向此缓存源码 install。最终 Lowcode 运行 JAR 内 13 个 Forge 依赖也全部匹配候选摘要，见 `packaged-forge-consumption.json`。CI 保留固定源码重建模式，来源同步为候选提交，与本地实际制品消费明确区分；未执行远程 Actions。

配套平台运行目录 `06-lowcode/platform-54115992/` 来自固定候选源码。IAM/platform/gateway 的 JAR 复用工单 01 的同源码构建，已确认 `54115992..e33dd537` 相关服务、SDK、前端应用与包源码无差异；JAR 摘要见 `runtime-provenance.json`。Shell/Console 在此临时源码副本重新构建。Lowcode 自身 JAR 和 remote 构建输出另存 `outputs/`，运行器使用 `--skip-build`，避免源码 install 覆盖实际候选消费证据。

## 必要改动与通用清单回填

- 原 V11 已有 `purpose`，本次只从候选实际 storage JAR 提取 `upgrade-retention.sql` 新增 V12，补齐 `retention_expires_at` 与 `expired_at`。V1—V11 文件摘要不变；旧 STORED 文件保留截止为空，`screen-image` 原用途、草稿与发布快照引用保留。
- storage 的传递 scheduling 任务要求 IAM 地址与服务凭据。配置引用已有 Manifest 属性，Ops Token 继续独立。未改领域模型或引入新的保留策略。
- 新父 POM 的 NeedBraces 覆盖测试代码，修正原 `DataPermissionTest` 四处单行 `if`，不跳过门禁。
- 规范副本取自候选实际生成工程；AGENTS 仅合入必要入口/主题/升级说明，保留本工程自有测试约定。
- 业务源码仍传 `/lowcode/...`。既有 `vite.config.ts` 已无 `apiPrefixes`；本次同步浏览器网络观察和 mock 到 `/api/...`，不改网关直连路径或业务路由。
- 独立宿主的 kit 只读取显式 `gateway` 选项，不读取 Shell 的 `FORGE_DEV_GATEWAY`。Lowcode Vite 现将该环境变量传给既有选项，未设置时保持 kit 默认；重新构建检查通过，生产 dist 与修改前逐文件零差异。
- 统一配置也影响业务仓库自己的真实平台运行器：各进程须通过统一 `FORGE_OPS_TOKEN`/`FORGE_MANIFEST_TOKEN` 变量接收自己独立的令牌，并显式提供 `FORGE_BUSINESS_ZONE`。只调整隔离 runner，不切换共享环境。

以上实际发现已回填 [Business Service 指南的已有快照工程升级段](../../docs/business-service-guide.md#已有快照工程升级)，Lowcode 仓库另有 `docs/platform-upgrade.md` 的来源、差异与复现说明。总清单与发布结论由 08 汇总，不因本票完成直接宣称发布就绪。

## 验证与 TDD 证据

| 接缝 | 执行与结果 |
| --- | --- |
| 候选父 POM 门禁 | `01-red-startup.log`：原测试四处 NeedBraces 失败；修复后后续完整 verify 不跳过此门禁 |
| 候选实际启动 | `02-red-startup.log`：为定位下一片仅在临时副本跳过已知 Checkstyle 红灯，现有图片 HTTP 用例报 `forge.scheduling.iam-uri must be set`；配置补齐后进入真实 HTTP |
| 旧数据增量升级 | `03-red-retention.log`：同库 V11 既有图片下载 500，精确报缺少 `retention_expires_at`；V12 后 HTTP 10/10 通过（`11-green-http.log`），包含旧大屏读改、原图片字节下载、跨租户拒绝及既有图片用途/权限用例 |
| 夹具修正 | 首次 V12 后用例被对象 Key 安全校验正确拒绝 404；将夹具改为原 SDK 使用的 `<tenant>/<service>/<object>` 格式，未放宽生产校验，见 `09-green-http.log` 与后续绿灯 |
| 后端全量 | `14-maven-verify.log`：独立候选缓存 `verify -DforkCount=1`，185 测试，零失败/错误/跳过；NeedBraces、依赖门禁、架构、表注释、Spotless 均通过 |
| 前端直接检查 | 候选三包 `pnpm update` 后安装通过；`format:check`、隔离 lint、stylelint、类型检查和 build 通过 |
| 前端入口红绿灯 | 有数据的既有大屏列表/新增用例先因旧 mock 路径失败（`08-red-visual.log`），同步 `/api` 后桌面/笔记本 2/2 通过（`10-green-visual.log`） |
| 前端完整回归 | `12-visual-update.log`：130/130 通过；本轮同步更新 38 张既有 PNG，逐张带文件名走查，并抽查原图与旧基线；主色/中性色跟随候选，业务大屏设计内容保留。索引见 `visual-review/` |
| 真实平台与独立宿主 | 最终 `22-real-platform-e2e-final.log` 退出 0；同一轮新建 Compose project 中，Shell 1/1（34.1s）、独立宿主 1/1（4.1s），真实 IAM 登录、Nacos 路由、MQ 开通/审计消费、业务/S3 图片读写、发布/播放/续签、权限与数据范围边界全部通过；没有 HTTP mock |

真实运行器的中间失败均保留：`15` 为旧 Ops 配置键；`16`/`17` 为旧用例未展开菜单分组，最终统一通过 `navigateLowcode` 只在 `aria-expanded=false` 时展开，缺菜单/无权限仍会失败；`20` 中 Shell 已完整通过，独立宿主因 kit 未读取 Shell 环境变量而代理到 8080，显式转交既有 `gateway` 选项后重验为 `22` 全绿。不是放宽业务断言或以 mock 代替真实联调。

最终私有目录 `/private/tmp/lowcode-platform-cnm_t_er` 已复制到 `06-lowcode/e2e-final/`，六张实际截图均已查看。Shell 1440×1000、查看者另有 1024×768、独立宿主 1440×1000；未见异常 overlay 或加载遮罩。独立宿主 `--el-color-primary=#4f46e5`，body 底色 `rgb(248, 250, 252)`，已登录阶段控制台错误为空。最终 `knownPlatformBaseline=null`，本轮没有触发旧 demo 403 例外。退出已清理本轮 PID/Compose project 与卷。

全部完整容器 Maven 与 E2E 经过 `/private/tmp/v020-upgrade/run-heavy.py` 串行锁，不停止其他开发容器。浏览器插件未提供，使用既有 Playwright Chromium。模拟接口视觉回归与真实平台浏览器结果分别记录。

## 证据位置与边界

完整证据统一保存在 `/private/tmp/v020-upgrade/06-lowcode/`：`baseline.json`、候选消费摘要、红绿灯日志、全量报告 `surefire-reports/`、更新截图走查 `visual-review/`、实际构建输出 `outputs/` 及配套运行目录。真实平台原始运行目录含本轮随机密码、令牌和邀请链接，保持私有，不作为公开日志发送；交付仅引用已查看的无登录凭据截图及结果 JSON。

本票未发布 Maven/npm、未推送 Lowcode 分支、未执行生产或共享环境数据变更。Oracle slow-dialect profile 未启用；本次未改外部驱动或连接实现。远程 CI 与目标仓库发布后只读消费不在本票通过范围。候选制品源码或内容变化时，重新比较摘要并复验受影响的后端、前端和平台浏览器路径。
