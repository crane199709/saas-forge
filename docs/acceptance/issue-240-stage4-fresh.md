# #240 阶段 4 Fresh Chrome 两仓联合验收

状态：2026-10-16 的隔离 Fresh 轮次仍在运行，已完成真实 Chrome 选定产品路径、双 Tenant 隔离、正式 Authority 探针及 10 条成功事实的 Outbox→Audit 关联。**阶段 4 的 11 项浏览器、11 项 Authority、4 项 Audit 尚未全部完成，不能据此勾选或关闭 #240**。本轮脱敏摘要见 [2026-10-16 r4 进度](issue-240-stage4-fresh-20261016-r4.json)；以下旧轮次事实仅供诊断，不得并入本轮。

### 2026-10-16 隔离 Fresh r4

在 r2 的 Project Manifest 注册 403 后，已按用户批准，将仅有 `remote-delivery:manifest:register` scope 的本轮 CI Client 精确绑定 `project` 模块，废弃旧 handoff，并从新 Secret、网络和卷启动 r3。r3 验出 Tenant Access 的角色及成员角色异常经 Gateway 被转换为 502；修复已提交为 `42b83b5a`，定向测试与 r3 诊断运行通过。r3 在诊断时替换了容器，故不把其后结果算作正式 handoff。r2/r3 已停止，未删除各自隔离卷；未暂停 `icube-*`。

当前正式 r4 的 runId 为 `8fc09b2e-df2f-414e-b442-0f7bffa0c3c6`，Compose 项目 `sf-acceptance-8fc09b2e-df2f-414e-b442-0f7bffa0c3c6`，handoff SHA-256 为 `e6295dede97658720c91cdab18e0116d1f19a6d74f79840c74aefaeb0d816c7d`，有效窗口为 2026-10-16 02:06:42 至 2026-10-17 02:06:42 UTC。后端干净源码 `42b83b5ac5a9f72583eea0c2d83dc1222ff89ddb`，前端干净源码 `a2b2c74f76d757487c44b608d13b8e119d10ea26`，Client 固定 `0.6.0`，锁文件 SHA-256 `6a8a13730d4290aa08c7a92ee54ae0b766aa25993e6ff4f51ea6e448ee8f427b`。主机 JDK 17 离线 Maven `clean package` 后，七个应用装入经验证的 JDK 17 运行基镜像；这不是七个 Dockerfile 的完整构建。冻结的七个应用容器仍按 handoff 原 ID 运行，环境使用本轮独立网络、卷与受限 Secret。Chrome 为 `155.0.8059.85`，与 [2026-10-15 Mac Stable 公告](https://chromereleases.googleblog.com/2026/10/stable-channel-update-for-desktop_01886704284.html)一致。

IAM/Gateway Nacos Console v2 配置回读与来源逐字节一致，七个工作负载的最小 ACL 校验通过。先完成 handoff `ready` 与 Gateway/Origin 探针；再用本轮限权 CI Client `01a14275-a56a-7731-9747-5f97065f1d5c`（仅 `remote-delivery:manifest:register`）经正式类型化 API 注册 Project Manifest，并由真实 Chrome 平台页面审批、启用。隔离 Frontend 147 个冻结产物逐文件等于当前前端构建，实际预览页面的 provenance、Client 版本和锁文件匹配；Chrome 会话冒烟 9/9 通过。两个新 Tenant 的管理员经 Mailpit 一次性链接，在真实 Chrome 完成设密与登录。Example 权限目录通过仓库受控导入脚本写入本轮隔离库，并经正式 Gateway 回读 10 个代码。

Tenant A 的真实 Chrome 观察覆盖两个组织根、带 Project 权限的角色、邀请创建及五字段品牌预览/保存，4/4 通过；Tenant B 的品牌上传、预览、保存及刷新回读 4/4 通过。两 Tenant 品牌五字段和四个公开 PNG 均经正式 Gateway 双向回读。已提交的 10 个组织、角色、邀请及品牌操作，逐一对应唯一的源 Outbox 快照与持久 Audit 行，已知敏感值检查通过；其范围仅为 `linked-observations-only`。第二位受邀成员又经真实 Chrome 完成邮件设密和接受邀请。正式 Authority 探针 4/4 通过：双 Tenant 组织/角色/成员/品牌隔离、Project/Task 十项 CRUD 与外租户拒绝、`project_app` 受限账号的双 Tenant RLS、末管理员并发仅一项成功及撤权后旧 Token 立即拒绝和最后管理员自撤销 409。所有这些报告同属 r4 的 handoff，未在 r4 替换应用容器。

精确后端提交的 [Verify](https://github.com/crane199709/saas-forge/actions/runs/38838158856) 已成功，包含 JDK 17、Nacos 权限和独立 Fresh Tenant 生命周期；精确前端提交的 [Frontend verification](https://github.com/crane199709/saas-forge-web/actions/runs/38724192604) 已成功。后端 [API Client artifact](https://github.com/crane199709/saas-forge/actions/runs/38839412744) 为本轮精确后端提交另行触发且已成功。当前 r4 仍保留运行以供后续验收；清理只限本轮项目，由环境准备方负责。完整 11/11/4 场景、Outbox 重试/失败事务、故障恢复、品牌素材真实 24 小时和 7 天清理及同轮聚合尚未执行，不把选定路径外推为阶段通过。

### 2026-10-15 重启轮次进度

本轮 runId `235ada91-757a-429b-b7fe-037683961218`，Compose 项目 `sf-acceptance-235ada91-757a-429b-b7fe-037683961218`。后端源码为干净提交 `7f6fbbb8e5bc34b51913989880306096ebefaf5f`，前端源码为干净提交 `59811e9d0028d2a0a50c40ef245f2ba9eecb928c`；前端锁文件 SHA-256 `6a8a13730d4290aa08c7a92ee54ae0b766aa25993e6ff4f51ea6e448ee8f427b`，使用正式 Client `0.6.0`。本轮 handoff SHA-256 `f21c126b5b871c1c1c544a6e06f22b3694e04532f37cff575c78874225f4ae22`，后端最小探针通过。对应 [后端 Verify](https://github.com/crane199709/saas-forge/actions/runs/38689684783)、[API Client artifact](https://github.com/crane199709/saas-forge/actions/runs/38705856249) 和 [前端 Frontend verification](https://github.com/crane199709/saas-forge-web/actions/runs/38689240355) 均对精确提交成功。

七个应用与 PostgreSQL、Redis、Kafka、Nacos、Mailpit、固定版 Silo、HTTPS Edge 均在该项目运行；没有暂停或修改 `icube-*` 容器。Gateway、IAM、Tenant Access、Entitlement 用当前提交的原始 Dockerfile 构建；Audit、Project、Remote 的 Docker 构建遇到 Maven Central 下载截断，改由同一干净提交的 JDK 17 离线 Maven 制品封装进同一运行基镜像，并记录制品及镜像摘要。这是本轮构建方法偏差，不能说七个应用均由 Dockerfile 完整构建。Nacos 实际回读 IAM/Gateway 的 Console v2 开关为 `true`。Chrome 运行版本 `155.0.8059.80`，匹配文中列出的 2026-10-13 官方 Mac Stable 公告。

平台初始登录与改密、两个新 Tenant 的管理员密码设置及登录均由真实 Chrome 完成。正式 Service OAuth2 Client 注册 Project Remote Manifest，平台真实 Chrome 将其从待审核依次审批、启用。Tenant A/B 分别经正式 Console 上传 Logo 与 Favicon、完成五字段隔离预览和保存，并在刷新后读回不同的品牌哨兵；正式 API 也核对两方各自五字段及素材 URL 不同。Tenant A 通过真实 Chrome 完成两个根组织、带 Project 权限的角色、邀请创建和品牌五字段预览保存四条选定观察路径。观察脚本原先使用错误的语言存储键，且直接读取 DevTools 中已被回收的响应正文；修正驱动并换用唯一邀请地址后的四条路径诊断重测通过，报告 SHA-256 `20e29002cd3a7a5467ac2d4bd1e6c56c8735cb9ac7273c5060ef833ec0896785`。该诊断报告明确记录前端 `dirty=true`、范围为 `selected-real-chrome-paths`，前端脚本修改尚未进入本轮冻结前端提交，因此不能替代 11 项完整浏览器验收。

联调定位到两个环境缺口：Project 工作负载的 Nacos ACL 缺少 `gateway` 服务发现读取权，正式 Project 请求持续返回 503；精确仓库修复已准备，权限变更正待用户批准后在隔离 Nacos 应用。隔离 Tenant Access 起初未配置 Mailpit SMTP，邀请写入后投递失败并令浏览器收到 502；本轮仅重建该服务并补 `mailpit:1025` 及非敏感发件地址，随后已提交邀请通过正式 resend API 返回 `SENT`，Mailpit 和正式 GET 均读回 `SENT`。两个 Tenant 的正式权限目录回读包含全部 10 个 Example 权限码。对 A/B Tenant 创建、两方素材上传/品牌保存、A 邀请 resend 共 9 个已提交操作，源 Outbox 与 Audit Record 逐项唯一匹配且通过敏感字段检查；此结果仅是已观察成功事实关联，**不等于**四项完整 Audit 验收。这些前置故障期间的请求不可记作产品通过；本轮仍需重测其完整业务链。

禁用一个未绑定的 Tenant B 品牌素材后，Platform 管理 API 返回 200、源 Outbox 与 Audit 记录唯一匹配、Tenant Access 直连公开读取返回 404，但正式 Gateway 公开读取返回 502；根因是该服务的错误正文缺少 Gateway 要求的完整 Problem Details 字段。相同契约缺陷也使重复邀请冲突从预期 409 转为 Gateway 502。已在工作区修复相关异常映射，`Stage4ProblemMappingTest`、`BrandProfileControllerTest`、`BrandAssetPolicyControllerTest` 定向测试通过。将本地修复临时封装为单独的诊断镜像后，正式 Gateway 对已禁用素材返回含六个必需字段的 404，对重复邀请返回含六个必需字段的 409；两项仅证明修复方向，不能覆盖原镜像的 `failed`。诊断后已恢复原 Tenant Access 镜像 ID，但容器重建使容器 ID 不再等于本轮冻结 handoff，因此**本轮后续运行态结果不能并入该 handoff 的正式验收**；修复提交和 CI 完成后须创建新 runId 重新冻结环境。四个已绑定素材的公开 PNG 检查通过，禁用测试不涉及它们。权威与 Audit 的脱敏分项状态见 [本轮独立摘要](issue-240-authority-audit-fresh-20261015.json)。

修复已分别提交为后端 `854d6f06`（错误契约）、`62810cd6`（隔离 Mailpit）和前端 `a2b2c74`（浏览器观察驱动）。定向后端测试 10/10、Compose 布局检查、前端类型检查和全仓 ESLint 通过；前端提交钩子完整运行。前端 `a2b2c74` 的 [Frontend verification](https://github.com/crane199709/saas-forge-web/actions/runs/38724192604) 已成功。后端 `f1fc6f22` 的 [Verify](https://github.com/crane199709/saas-forge/actions/runs/38724199163) 已结束：JDK 17 后端与契约、Fresh Tenant 生命周期两项成功；Nacos 配置与权限 job 的静态校验、发布及权限回读通过，但独立故障恢复步骤的 `nacos-init` 退出 1，整条 Verify 失败。该失败日志未给出容器内根因，不能当作通过；新增 ACL 后需在最终提交的 CI 复测，若重现则继续诊断。用户已批准仅给隔离环境 `project-service-dev` 增加 `dev:DEFAULT_GROUP:naming/gateway:r`，精确源补丁及 ACL 校验已准备；正式运行态应用和对应最终提交的 API Client artifact、新 Fresh 环境尚未执行。

旧轮已执行本轮 Compose 项目的 `stop`，核对后无该项目运行容器；PostgreSQL、Redis、Kafka、品牌存储的四个隔离卷仍保留，未运行 `down -v`。`icube-*` 四个容器继续运行，其他项目未被停止。旧轮不再追加正式验收结果；新轮必须使用新 runId、重新冻结容器身份与源码制品。

目前**未形成**阶段 4 全部 26 项同轮通过报告。特别是品牌素材 24 小时和 7 天清理需要真实时间窗，不能通过调整时间、修改数据库或调用聚合器代替。其余浏览器、权威、故障和 Audit 项按下面的必需证据逐项完成后，再运行聚合器；缺项保持 `not_run`，保留 #240 开放。

## 当前可核对的基础

后端 `scripts/acceptance-handoff.mjs` 的 `prepare-stage3` 可先保留新 runId，生成七服务隔离 Compose 覆盖；`ready` 核验项目网络、PostgreSQL/Redis/Kafka 新卷、七服务镜像来源与容器、JDK 17、Gateway 与受信 HTTPS 公钥一致性。`probe` 只验证 Gateway 公钥与精确 Origin。前端独立仓库的 `scripts/verify-browser.mjs` 只覆盖会话交接冒烟；阶段 4 专用聚合器 `scripts/stage4-aggregate.mjs` 已合入前端集成分支，本地定向测试 9/9 通过。不能把冒烟结果改名当完整报告。旧 Console 已迁出，本仓不读取前端源码或运行浏览器。

2026-10-14 本机已安装 Java `17.0.12`、桌面 Google Chrome `155.0.8059.80`。Google [2026-10-13 Desktop Stable 公告](https://chromereleases.googleblog.com/2026/10/stable-channel-update-for-desktop_01668083673.html)列出 Mac `155.0.8059.79/.80`；正式执行日仍需重新核对最新公告，并以报告中的 Chrome 运行版本为准。2026-10-15 已通过本机 Docker 运行定向 S3 Testcontainers 测试；未因此运行或判定完整 Fresh 环境。

2026-10-15 前端集成分支的预检提交 `2f95dd2bbd54c70a5e9222be0e39e5951e50b3f8` 已包含 #235、#238、#239、#240，`package.json` 与 `pnpm-lock.yaml` 均固定正式 Client `0.6.0`。本地 `pnpm install --frozen-lockfile`、`pnpm run verify`（276/276 Node 测试及构建）和隔离 Chromium UI（32/32）通过；这些检查不能代替真实 Chrome/Gateway 联调。品牌 S3 兼容存储叠加已提交，受控 v2 实际运行值、完整 Fresh 部署及产品验收仍待验证。

### 2026-10-15 精确提交 CI 与预检结果

后端源码提交 `6acb5d5794fc3b78e36aae61baf0d0f73e6d8d10`、前端源码提交 `2f95dd2bbd54c70a5e9222be0e39e5951e50b3f8` 均在预检时无未提交改动。对应 [后端 Verify](https://github.com/crane199709/saas-forge/actions/runs/38686725682)、[API Client artifact](https://github.com/crane199709/saas-forge/actions/runs/38686756384) 与 [前端 Frontend verification](https://github.com/crane199709/saas-forge-web/actions/runs/38676569811) 均成功；后端 Verify 包含 JDK 17、Nacos 和独立 Fresh Tenant 生命周期，但它不是阶段 4 的七服务同轮验收。正式 Client `0.6.0` 的包和来源摘要见下节。

为阶段 4 准备的全新隔离轮次 `1f1a3a84-8350-48ff-bbb9-50737ca61623` 只完成**不启动容器**的严格预检：七应用镜像构建来源指向上述后端提交，32 个本轮 Secret 挂载及两个 TLS 挂载均只读，冻结前端制品树 SHA-256 为 `f9fd428f9edc0c7db02d8aa409d6465038ea830026465a52cab23d27fbb1eae2`，与当前前端源码构建逐文件一致。本机 Chrome `155.0.8059.80` 与 2026-10-13 官方桌面稳定版公告一致。此轮次仅为 `prepared`，**没有 `ready` handoff、运行镜像、容器或 26 组业务报告**。

部署资源门禁实测 Docker Desktop 限额约 7.75 GiB，其他项目 13 个容器占约 5.02 GiB，仅余约 2.73 GiB；七应用、Kafka 与 Nacos 的配置堆上限合计约 2.19 GiB，尚未计入 JVM 非堆、数据库、S3 与 Edge。用户选择保留其他项目运行并暂缓 Fresh，因此本轮在构建前停止，未停止、暂停或修改其他容器，也未占用 443/5174。恢复验收时应重新生成隔离 Secret/runId，并重核两仓提交、CI、Chrome 版本、端口与资源；旧 prepared 轮次不能充当直接验收证据。

预检之后，规范审查发现权限目录曾绕过共享类型化 Client 自行注入 Bearer；前端提交 `59811e9d0028d2a0a50c40ef245f2ba9eecb928c` 已改用正式 `PermissionsApi.listPermissions()`。定向测试 10/10、类型检查、ESLint 与提交钩子通过；该提交的 [Frontend verification](https://github.com/crane199709/saas-forge-web/actions/runs/38689240355) 已成功。**上面的冻结制品和预检仍对应旧前端提交 `2f95dd2b`，不能与新提交混用**；恢复 Fresh 时必须重新构建前端并创建新轮次。

### 正式 Client 发布与前端升版

2026-10-15，后端 `package.json` 和 `package-lock.json` 已固定为 `0.6.0`；经批准的 `npm publish` 命令成功返回。官方 npm registry 的只读查询已返回 `0.6.0`，从 registry 下载的 tarball SHA-256 为 `ee9f7c30f095064c908cf3b3ddc0f081469f6807c08333a26fdadafb17cd6f51`，与发布候选包逐字节一致；包内 `contract-source.json` 指向提交 `3dd2ca04b26bb66ce2d5705c939aa6216abe5955`，`dirty=false`。这些结果证明制品已传播且来源匹配，不代表两仓 CI 或阶段 4 Fresh 验收通过。

后端维护目录为 `saas-forge-contracts/saas-forge-openapi-contracts/typescript-client/`。同步修改其 `package.json` 与 `package-lock.json` 的版本；可用 `npm version <新版本> --no-git-tag-version` 生成一致改动。随后在该目录执行 `npm ci`、`npm run build`、`npm pack`，再将 tarball 安装到仓库外目录做生成 operation 导入与请求/反序列化检查。构建用 JDK 17、根 Maven Wrapper 和正式 v1/v2/Project 契约，包内 `contract-source.json` 记录来源提交、dirty 与输入 SHA-256。冻结代码并提交后在干净工作区复打包，使来源显示 `dirty=false`；`prepublishOnly` 会拒绝有未提交改动的发布。后端 `Verify` 与 `API Client artifact` CI 必须对应此提交，不能引用 0.5.0 的历史运行。

正式 `npm publish` 是公开且不可覆盖的制品发布，需要发布者 npm 登录/2FA 与用户对具体制品的批准；仓库目前没有 npm trusted publisher。本次已获批准，registry 版本、`contract-source.json` 和完整性核对结果见上文。前端已固定 `@crane199709/saas-forge-api-client@0.6.0` 并完成本地冻结安装与验证；`pnpm-workspace.yaml` 另为已审阅的精确版本 `0.6.0` 增加发布时间例外。前端 `build/provenance.ts` 仍要求正式安装包版本与锁文件一致、包来源 `dirty=false`。真实 Chrome/Gateway 验证仍待本轮 Fresh 环境执行。

## 同轮执行顺序

1. 固定并记录两仓最终提交、dirty 状态、正式 Client 的已发布版本与来源、前端锁文件 SHA-256，运行后端 `Verify`、`API Client artifact` 和前端 `Frontend verification`，保存每个 workflow 的成功 run URL 与精确 head SHA。后端 `Verify` 包含 JDK 17 服务/契约、Fresh Tenant 生命周期及 Nacos/Compose 门禁；前端 `verify.yml` 包含类型、Lint、翻译、Node 测试、构建及生产 UI/a11y/视觉。CI 不取代下述阶段 4 Fresh 产品验收。
2. 环境准备方在受限目录创建专用 Secret、邮件、S3 与服务身份材料，只用于本轮隔离项目；不复用日常开发数据卷。以受控部署方式在所有参与实例启用认证 v2，证明实际运行值。先保留轮次：

   ```sh
   node scripts/acceptance-handoff.mjs prepare-stage3 "$SF_ROUND_DIR" "$SF_CONSOLE_ORIGIN" "$SF_API_ORIGIN"
   ```

   `$SF_ROUND_DIR` 必须尚不存在，父目录已存在。最终 Compose 需合并现有 `deploy/acceptance/compose.yaml`、`tenant-lifecycle-e2e.override.yaml`、`stage3.override.yaml`、`brand-storage.override.yaml` 和本轮生成的 `compose.override.yaml`；实际组合应先由 `docker compose config` 与布局校验核对，再执行 build/bootstrap/up。品牌存储叠加已定义，但完整 Fresh 仍需本轮受限凭据、受信 Edge 和制品，本页不提供假定可运行的完整启动命令。
3. 部署方完成镜像构建、迁移、引导、受信 HTTPS Edge、正式 Console/Remote 不可变制品及 Mailpit/品牌存储准备后，运行：

   ```sh
   node scripts/acceptance-handoff.mjs ready "$SF_ROUND_DIR"
   node scripts/acceptance-handoff.mjs probe "$SF_ROUND_DIR/handoff.json" "$SF_ROUND_DIR/backend.json"
   ```

   `ready` 与 `probe` 通过只表示同轮环境身份和最小 Gateway 安全边界；不能代替业务场景、审计或品牌隔离。运行时记录所有镜像/容器 ID、网络/卷、Gateway/Edge、JDK、受控 v2 开关和清理责任。
4. 前端使用正式固定 Client 和真实当前稳定版 Chrome，在本轮 Console/Remote 路径执行组织/角色/邀请/成员/恢复/平台生命周期/品牌/10 CRUD、中文英文、键盘、无障碍、视觉、拒绝与恢复；浏览器自行管理 Cookie、Origin 和 Fetch Metadata。后端独立通过正式 API、受限服务账号/数据库和故障注入窗口验证并发保护、历史 Token、补偿、RLS、即时撤权、品牌存储与服务恢复。每次注入记录准确作用范围、时间、结果和恢复，不以手工改状态冒充真实等待。
5. 对上述**本轮**成功变更，按源事件 ID、Outbox、Audit Record 精确匹配持久行；验证重复投递幂等、事务失败无虚假成功及敏感材料缺失。所有报告使用同一 runId/handoff SHA-256，并按通过、失败、跳过、未执行分别记载。用前端阶段 4 聚合器离线复核，随后把脱敏原报告、散列清单、两仓 CI、诊断失败及最终结论写入 `docs/acceptance/`。本地 `.scratch/` 不能作为长期验收证据。
6. 环境准备方按本轮项目名记录并执行清理决定。故障排查期间可保留受限环境；清理时只操作本轮 Compose 项目，不删除共享镜像、开发数据卷或其他环境。记录停止/保留的资源及责任人。

### 同轮成功事实关联探针

`scripts/stage4-audit-links.mjs` 只读取本轮 Fresh PostgreSQL，以操作的准确时间窗、源服务、action、resourceId、actorIdentityId 定位**唯一**持久 Audit 行，再按其 sourceEventId 关联源库中唯一已发布 Outbox 快照；还核对事件类型、时间与 trace，并拒绝已知敏感键及本轮受限文件中的秘密值。探针等待最多约 60 秒，以容纳异步投递。它不执行产品操作，也不覆盖重投递幂等或失败事务，因此输出为 `kind=stage4-audit-links`、`scope=linked-observations-only`，不能直接作为四项全部通过的 `stage4-audit` 报告。

先将真实操作的时间窗、正式 API 响应资源 ID 与已验证的当前 Identity ID 写入仅本轮可读的 `audit-observations.json`（权限 0600），并列出本轮生成的纯文本 Secret 文件路径（同一所有者/组、权限 0600 或 0640）；不得填写旧轮事件 ID、Cookie、Token、密码或原始邮件。每个 `operations` 条目须含 `label`、`source`（`iam`、`tenant-access` 或 `project`）、`action`、`resourceId`、`actorIdentityId`（系统事件可为 `null`）、`startedAt`、`finishedAt`。顶层还需 `runId` 和非空的 `sensitiveFiles` 数组。时间窗必须位于 handoff 的 `readyAt` 与 `expiresAt` 内；同一操作若命中多条事实，探针拒绝歧义，需重做可区分的操作。具体 action/resource 的白名单以各 Audit EventValidator 和正式 API 响应为准。

```sh
chmod 600 "$SF_ROUND/audit-observations.json"
node scripts/stage4-audit-links.mjs \
  "$SF_ROUND/handoff.json" "$SF_ROUND/audit-observations.json" \
  "$SF_ROUND/audit-links.json"
```

输出仅含本轮 runId、输入文件哈希、每项状态和加盐事件 ID 哈希，不输出源事件 ID 或秘密值。原始观察文件与查询权限由环境准备方保管；长期证据只提交脱敏报告和哈希。此探针使用 Fresh PostgreSQL 管理连接读取内部表，**不构成**运行时数据库账号 RLS 通过证据。

### 后端 Authority 探针入口

`scripts/stage4-authority-probe.mjs` 只接受 `ready` 的同轮 Fresh Compose handoff，校验 Gateway JWKS、隔离 PostgreSQL 容器/卷和当前 Tenant JWT；通过正式 HTTPS Gateway 请求当前服务。它以两位 Tenant 管理员和各自的可区分组织、角色、成员、品牌资料，双向核对只读隔离；随后执行 Project/Task 各五种 CRUD、跨 Tenant 资源拒绝，并使用 **`project_app` 受限数据库账号** 在无 Tenant、Tenant A、Tenant B 三种上下文下查询 Project/Task RLS。最后在恰有两名管理员的 Tenant 内并发互相撤销管理员角色，核验只成功一项、保留最后一名管理员、旧 Token 的下一次管理请求立即 403、自撤销 409。它不会写数据库或直接修改角色；所有业务变化均经正式 API。

执行前由环境准备方在本轮受限目录创建权限 0600 的 `authority-scenario.json`、两 Tenant 用户 Access Token 文件、并发测试两位管理员的用户 Access Token 文件，以及本轮 `project_app`、`tenant_access_app` 密码文件。场景顶层只允许 `runId`、`tenantA`、`tenantB`、`lastAdministrators`、`projectAppPasswordFile`、`tenantAccessAppPasswordFile`；每个 Tenant 需 `tenantId`、`tokenFile`、`organizationUnitId`、`roleId`、`membershipId`、`brandDisplayName`；`lastAdministrators` 需 `tenantId`、`administratorRoleId` 及 `first`/`second`，每位包含 `membershipId`、`tokenFile`。两个 Tenant 的哨兵值必须不同。准备方需确认测试 Tenant 当前恰有两位有效管理员且允许本轮撤销其中一位；探针验证此条件后才并发请求。不要在仓库中保存 Token、密码或可提交配置模板。

```sh
node scripts/stage4-authority-probe.mjs \
  "$SF_ROUND/handoff.json" "$SF_ROUND/authority-scenario.json" \
  "$SF_ROUND/authority-probe.json"
```

脚本依赖本机受信 HTTPS CA、`curl`、`docker`、`psql`（在本轮 PostgreSQL 容器内）。无 `--insecure` 路径；Bearer Token 与数据库密码只经子进程标准输入传递。输出权限 0600，包含同轮 handoff/场景哈希、检查状态和资源 ID 的加盐哈希，不输出凭据。若项目操作中途失败，已创建的测试资源保留供诊断；环境准备方按本轮隔离项目清理，不能把失败前的局部操作记作通过。此探针不注入故障，不覆盖浏览器产品路径、补偿恢复或完整审计；其 `kind=stage4-authority-probe` 仅能作为所列权威检查的同轮证据。

## 必需证据与状态

| 边界 | 直接证据 |
| --- | --- |
| 浏览器产品路径 | 新旧受邀者、等待授权、组织多根树/移动/多归属、角色并集与委派保护、成员禁用/恢复、全局密码恢复、平台授权/撤权、Tenant 有效期/关闭、完整品牌/隔离预览/冲突/回退、Project/Task 各五种 CRUD 允许与拒绝、双语/键盘/a11y/视觉。 |
| 后端权威与故障 | 两 Tenant 的组织/角色/数据/品牌隔离、最后管理员并发保护、Invitation/Quota 补偿、成员撤销与计量恢复、Recovery Fence、历史平台 Token、Closure、素材校验/公开读取/禁用/清理、撤权后新请求即时拒绝、权威不可用时失败关闭、Example RLS/版本/幂等。 |
| 成功事实与 Audit | 平台角色、组织、成员、邀请、权限、品牌及适用业务变更的源事务、Outbox、事件契约、Audit 持久行逐项一致；失败事务无成功事实，重复事件不重复记录，敏感值不入事件/证据。 |
| 来源与 CI | runId、handoff SHA-256、两仓源码提交/dirty、运行镜像、Console/Remote 制品、固定正式 Client 与锁文件、Chrome Stable 公告/运行版本、JDK 17、三条本提交 CI run、隔离/清理回执。 |

只有以上必需场景和门禁均有同轮直接通过记录，才得出阶段 4 结论。Project 导出与 Feature 联合验收属于阶段 5；完整拒绝 Audit 和公开查询/导出属于阶段 6。任何缺项保持未执行或失败，不能因局部单元测试、旧轮次或聚合器结构校验通过而关闭 #240。
