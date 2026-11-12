# 04 接入诊断验收

2026-11-12，独立分支 `codex/onboarding-04`，基于01合入后的 `35cd2ef`，最后合入集成分支 `c7e4b1`。

## 结果与配置

- SDK 初次 + 3 次异步重试（1/2/4 秒），随后每5分钟最多12次；成功/关闭停止。状态保留最近成功与最近失败，未成功不写已上报版本。Service Credential 与 Ops Token 分离；Servlet 引入 manifest 会带入 Ops 安全链，漏配 Ops Token 启动失败，已同步业务服务规范与 archetype 配置说明。
- 控制台 `/onboarding`，HTTP服务路径 `GET /onboarding`（经SDK浏览器为 `/api/platform/onboarding`），权限 `platform:onboarding:read`，唯一主操作为刷新。表格服务行可用 role=row + service 名定位。
- 平台网关 origin 必配 `forge.platform.onboarding.gateway-uri`；应用本地值来自 `FORGE_ONBOARDING_GATEWAY_URI`（本地模板8080），隔离E2E配置18000并注册三服务simple discovery，避免误连已有环境。
- 三级独立：Nacos配置源、lb选中实例状态、实际网关origin的 `/prefix/v3/api-docs` HTTP JSON/OpenAPI/服务身份校验。没有可读取的Nacos前缀则请求未探测，不引入第二份服务前缀配置。

## 自动验证（实际执行）

- SDK最终同reactor定向：`./mvnw -o -pl forge-sdk/forge-sdk-manifest,forge-sdk/forge-sdk-ops,forge-sdk/forge-sdk-openapi -am test -Dtest=ManifestReporterTest,ManifestReportingTest,OnboardingStatusHttpTest,OpsHttpTest,ServiceIdentityHttpTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false` 通过。manifest 15项、ops 5项、openapi 2项，另core/web/security各1项架构检查通过。日志 `/private/tmp/onboarding04-sdk-final.log`。
- 平台定向：`OnboardingDiagnosticsTest` 5项 + `ArchitectureTest` 1项同reactor通过；此前 `OpsChannelTest` 4项与诊断初始3项/架构1项通过。日志 `/private/tmp/onboarding04-reactor.log`、`/private/tmp/onboarding04-platform.log`。包括无诊断权限403/Tenant401、局部服务失败、目录失败、Nacos DOWN与读取中掉线、HTML200和错误身份200不通过。ConfigService是外部配置源夹具，SDK状态及网关探测用真实JDK HttpServer传输；没有在本票内启动真实Nacos或真实网关进程。
- SDK更新曾先执行 `-am install -DskipTests`；其他并行任务覆盖共享m2后，单模块平台检查遇旧SDK制品缺少新状态类型，改同reactor验证通过，不将该环境覆盖计作实现缺陷。
- 前端定向 `OnboardingPanel.spec.ts`、`App.spec.ts`、`MenuLayoutPage.spec.ts` 共12项通过；platform-console typecheck、typecheck:tools、lint:style、所有修改前端文件Prettier检查通过。日志 `/private/tmp/onboarding04-ui3.log`、`onboarding04-typecheck3.log`、`onboarding04-tools2.log`、`onboarding04-style.log`、`onboarding04-formatcheck.log`。
- 既有三个应用构建通过；Vite保留已有大chunk提示，无构建错误。
- Browser plugin not available，使用既有Playwright。`pnpm exec playwright test -c playwright.visual.config.ts onboarding.spec.ts --update-snapshots` 6项通过；逐张查看六张PNG后，同命令去掉更新参数比对6项通过。日志 `/private/tmp/onboarding04-visual.log`、`/private/tmp/onboarding04-visual-compare.log`。

## 浏览器走查

目标流程：`/onboarding` → 刷新 → 独立三级状态保持；展开 → 版本/时间/配置来源显示。URL/标题/有内容、无框架错误覆盖层、无相关console/pageerror、刷新确实增加一次GET、展开可见长版本全部通过。错误态503浏览器资源错误为预期，页面显示可追踪错误提示。1440×900与1024×768覆盖混合状态（健康+配置未知+后台失败）、空列表、读取错误；六张基线位于 `frontend/e2e/visual/__screenshots__/onboarding-*.png`。主按钮/表头/标签不折行；长服务名/缺项使用tooltip，1024下表格可横滚，三级主状态可读。

## 证明范围与未执行项

- Nacos来源明确为客户端，可能含snapshot/failover；前后UP也不是原子远端最新配置证明。配置存在不代表网关已运行加载。
- 运行状态只代表lb选中实例。IAM自身直接注册清单，未提供reporter端点时显示不支持；不从逻辑清单目录名推导运行实例。
- 请求通过只证明该次网关接口文档路由及服务声明身份；不证明业务授权、其他副本、Shell `/api` rewrite或共享环境。
- 本票没有执行模块全量；由root在整份规格集成后统一跑一次。真实隔离全栈入口由root补充验证；本票未宣称真实Nacos部署与共享环境验收通过。
- 两个新管理页面合并后对既有菜单布局基线的变化由root统一生成和查看；本票只提交自身六张新基线。
