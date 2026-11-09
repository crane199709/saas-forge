# 租户 IP 允许列表验收

日期：2026-11-09。集成分支：`codex/tenant-ip-allowlist-integration`。审查基点：`0fd438cc13572da1fdccd052a66701340bafd872`。功能提交：`f5196b8`、`edb3f36`、`2597ca2`、`6e80642`；审查修复：`e63a88e`，集成至 `ebdc15b`。

## 实现结果

- 工单 01–04 已 resolved：规则管理与 Redis 发布恢复、Token 与网关校验、Operator 紧急清除、租户端与平台端界面。
- IPv4/IPv6/CIDR 字面量规范化，最多 100 条、拒绝 `/0`，空列表无限制；保存必须包含当前可信来源 IP。沿用 IAM tenant 表新增 V27 列与中文描述，无新增表。
- IAM 同步数据库事务与 Redis 发布，真实 Redis 拒写会回滚；启动按租户行锁全量恢复，新 Tenant 与刷新补齐规则键。网关一次 MGET 读取版本、状态、规则；缺失/非法规则与来源不匹配为 403 tenant.ipNotAllowed，Redis 故障仍为 503。
- exchange、switch、refresh 共用准入校验，审计更新、清除与签发拒绝；网关逐请求拒绝不写审计。登录保持全局，同一 Identity 的其他 Tenant 不受影响。
- 拒绝页面保留凭证，可切其他 Tenant 或退出；覆盖首屏/reload、目标拒绝保留当前 Tenant、暂停业务轮询和无刷新循环。恢复列表凭有效 Tenant Refresh Token 只读获取，不签发/轮换、不附 Bearer/Cookie。
- Platform 清除复用 platform:tenant:manage，校验 Realm、Operator 状态和非空原因（最多 500），目标规则立即发布为无限制，并记录原因与 Operator。

## 验证

| 检查 | 结果 |
| --- | --- |
| 当前源码 SDK 与服务 install，含后端 Spotless | 通过 |
| SDK Core 全量 | 29 项通过，0 失败/错误/跳过 |
| Gateway 全量 | 50 项通过，0 失败/错误/跳过 |
| IAM 全量 | 278 项通过，0 失败/错误/跳过 |
| Tenant App 全量 | 31 文件，187 项通过 |
| Platform Console 全量 | 13 文件，73 项通过 |
| SDK、两个 Shell、工具 typecheck | 通过 |
| 两个 Shell、Demo 两版本 build、修改文件 Prettier | 通过 |
| Playwright 新流程 | 10 项通过；安全设置截图与错误样式另复验 2 项通过 |
| 最终前端 SDK 的真实登录/数据范围/切 Tenant | 1 项通过；隔离 PostgreSQL/Redis 与真实网关服务，容器已清理 |
| 审查修复定向复验 | IAM 85/85、前端 6/6、Playwright 桌面/移动 2/2；类型、格式、构建通过 |

全量基线取功能集成完成后的源码；审查修复后只复跑直接受影响的检查，详见 [修复记录](review-fixes.md)。

后端模块全量使用 `-DforkCount=1`；前端两个模块以 `--maxWorkers=2` 顺序执行。迭代曾遇 Docker 容器启动波动和前端并行负载超时，串行复跑通过，未放宽测试超时或修改容器基础设施。

```sh
cd backend
./mvnw -o -pl forge-iam,forge-gateway,forge-sdk/forge-sdk-core -am install -DskipTests
./mvnw -o -pl forge-sdk/forge-sdk-core,forge-gateway,forge-iam test -DforkCount=1
cd ..
MAVEN_ARGS=-o bash deploy/e2e/run.sh --grep '双 Membership 登录、数据范围与租户切换'
```

前端命令及公开 HTTP mock 范围见 [工单 04](issues/04-allowlist-ui.md)。浏览器使用 Chromium，Browser plugin not available，采用仓库 Playwright；Tenant 127.0.0.1:5174 / Platform 127.0.0.1:5173，1280×900 与 390×844。页面身份、非空、无框架 overlay、console/pageerror 和交互检查通过；已查看安全设置、拒绝页面和清除对话框截图。

| 浏览器检查 | 结论 |
| --- | --- |
| 页面 URL/title 与有效内容 | 通过 |
| 框架覆盖层、相关 console/pageerror | 无异常 |
| 规则保存、具体条目错误/焦点、当前 IP 与生效提示 | 通过 |
| 首屏/reload/普通请求/刷新/换码/目标切换被拒后的恢复 | 通过 |
| 清除确认、必填原因、取消及只读权限 | 通过 |
| 桌面与 390px 视口 | 通过，修复焦点与窄屏输入遮挡后复验 |

## Standards

初审 2 项硬规范违例、1 项判断型重复逻辑，均已修复并核对：IAM 使用独有的 tenant.ipAllowlistNotFound；缺失/空白字段 userTip 回退通用中文提示；可信 IP 提取集中到 IAM ClientIpResolver，保持原有 null 与来源头信任约定。

父集成已核对完整修复差异与定向验证结果。剩余发现 0 项，详见 [修复记录](review-fixes.md)。

## Spec

原审查发现 0 项规格偏差：缺失/部分实现、越范围、错误实现均为 0。POST memberships 为拒绝后切换 Tenant 所需恢复路径。最终修复没有改变已约定功能，复核后剩余 0 项。

## 上线顺序与边界

必须先部署 IAM（含 V27）并完成全量发布，再启用网关校验；Redis 丢失后同样先完成 IAM 恢复。详细操作与可信头直连约束见 [部署说明](../../deploy/images/README.md)。本次仅本地实现、提交与测试，没有推送或部署。

新增 IP 拒绝流程的浏览器验收使用公开 HTTP 模拟；真实 IP 判定/跨 Tenant/审计/Redis 故障由后端集成测试覆盖。真实服务 E2E 覆盖正常登录与切换，未用真实代理切换客户端来源来串起保存→拒绝→清除整条链；未覆盖 WebKit/Firefox。

## 本地证据

全量日志：`/private/tmp/tenant-ip-allowlist-final-install.log`、`tenant-ip-allowlist-backend-full.log`、`tenant-ip-allowlist-real-e2e-final.log`。前端和截图证据见工单 04；原审查报告为 `/private/tmp/tenant-ip-allowlist-review-standards.md` 与 `tenant-ip-allowlist-review-spec.md`。临时日志/截图不提交仓库。

![拒绝页面](/private/tmp/tenant-ip-allowlist-denied-desktop.png)
![平台清除确认](/private/tmp/tenant-ip-allowlist-clear-mobile.png)
