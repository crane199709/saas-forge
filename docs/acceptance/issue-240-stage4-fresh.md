# #240 阶段 4 Fresh Chrome 两仓联合验收

状态：准备中。当前仅建立同轮证据判定入口；**未运行阶段 4 Fresh Compose、真实 Chrome 产品路径、权威探针或两仓新提交 CI**，不能据此勾选 #240。

## 当前可核对的基础

后端 `scripts/acceptance-handoff.mjs` 的 `prepare-stage3` 可先保留新 runId，生成七服务隔离 Compose 覆盖；`ready` 核验项目网络、PostgreSQL/Redis/Kafka 新卷、七服务镜像来源与容器、JDK 17、Gateway 与受信 HTTPS 公钥一致性。`probe` 只验证 Gateway 公钥与精确 Origin。前端独立仓库的 `scripts/verify-browser.mjs` 只覆盖会话交接冒烟；阶段 4 专用聚合器 `scripts/stage4-aggregate.mjs` 目前仅在 #240 前端工作树暂存，尚未合入前端集成分支，正式使用前须确认其提交与验证状态。不能把冒烟结果改名当完整报告。旧 Console 已迁出，本仓不读取前端源码或运行浏览器。

2026-10-14 本机已安装 Java `17.0.12`、桌面 Google Chrome `155.0.8059.80`。Google [2026-10-13 Desktop Stable 公告](https://chromereleases.googleblog.com/2026/10/stable-channel-update-for-desktop_01668083673.html)列出 Mac `155.0.8059.79/.80`；正式执行日仍需重新核对最新公告，并以报告中的 Chrome 运行版本为准。2026-10-15 已通过本机 Docker 运行定向 S3 Testcontainers 测试；未因此运行或判定完整 Fresh 环境。

当前前端 `package.json` 和 `pnpm-lock.yaml` 固定 `@crane199709/saas-forge-api-client@0.5.0`，尚未包含阶段 4 全部正式 operation。#235、#238、#239 的前端切片与最终两仓集成、正式 Client 的前端锁文件更新、受控 v2 开关与 Git hook 处置仍需落实。品牌 S3 兼容存储叠加已提交，但完整 Fresh 部署及产品验收未运行。本记录不把这些前提写作已通过。

### 正式 Client 发布与前端升版

2026-10-15，后端 `package.json` 和 `package-lock.json` 已固定为 `0.6.0`；经批准的 `npm publish` 命令成功返回。官方 npm registry 的只读查询已返回 `0.6.0`，从 registry 下载的 tarball SHA-256 为 `ee9f7c30f095064c908cf3b3ddc0f081469f6807c08333a26fdadafb17cd6f51`，与发布候选包逐字节一致；包内 `contract-source.json` 指向提交 `3dd2ca04b26bb66ce2d5705c939aa6216abe5955`，`dirty=false`。这些结果只证明制品已传播且来源匹配，不代表前端升级、两仓 CI 或阶段 4 Fresh 验收通过。

后端维护目录为 `saas-forge-contracts/saas-forge-openapi-contracts/typescript-client/`。同步修改其 `package.json` 与 `package-lock.json` 的版本；可用 `npm version <新版本> --no-git-tag-version` 生成一致改动。随后在该目录执行 `npm ci`、`npm run build`、`npm pack`，再将 tarball 安装到仓库外目录做生成 operation 导入与请求/反序列化检查。构建用 JDK 17、根 Maven Wrapper 和正式 v1/v2/Project 契约，包内 `contract-source.json` 记录来源提交、dirty 与输入 SHA-256。冻结代码并提交后在干净工作区复打包，使来源显示 `dirty=false`；`prepublishOnly` 会拒绝有未提交改动的发布。后端 `Verify` 与 `API Client artifact` CI 必须对应此提交，不能引用 0.5.0 的历史运行。

正式 `npm publish` 是公开且不可覆盖的制品发布，需要发布者 npm 登录/2FA 与用户对具体制品的批准；仓库目前没有 npm trusted publisher。本次已获批准，registry 版本、`contract-source.json` 和完整性核对结果见上文。前端在独立仓执行 `pnpm add --save-exact @crane199709/saas-forge-api-client@0.6.0`，审查 `package.json`、`pnpm-lock.yaml` 与包完整性，然后 `pnpm install --frozen-lockfile`、`pnpm run verify`，并以真实 Chrome/Gateway 验证。`pnpm-workspace.yaml` 的发布时间例外当前只含已审阅的旧版本；新版本若被年龄门禁阻止，应先核对实际发布来源再决定是否加入精确例外，不预先放宽。前端 `build/provenance.ts` 仍要求正式安装包版本与锁文件一致、包来源 `dirty=false`。

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

## 必需证据与状态

| 边界 | 直接证据 |
| --- | --- |
| 浏览器产品路径 | 新旧受邀者、等待授权、组织多根树/移动/多归属、角色并集与委派保护、成员禁用/恢复、全局密码恢复、平台授权/撤权、Tenant 有效期/关闭、完整品牌/隔离预览/冲突/回退、Project/Task 各五种 CRUD 允许与拒绝、双语/键盘/a11y/视觉。 |
| 后端权威与故障 | 两 Tenant 的组织/角色/数据/品牌隔离、最后管理员并发保护、Invitation/Quota 补偿、成员撤销与计量恢复、Recovery Fence、历史平台 Token、Closure、素材校验/公开读取/禁用/清理、撤权后新请求即时拒绝、权威不可用时失败关闭、Example RLS/版本/幂等。 |
| 成功事实与 Audit | 平台角色、组织、成员、邀请、权限、品牌及适用业务变更的源事务、Outbox、事件契约、Audit 持久行逐项一致；失败事务无成功事实，重复事件不重复记录，敏感值不入事件/证据。 |
| 来源与 CI | runId、handoff SHA-256、两仓源码提交/dirty、运行镜像、Console/Remote 制品、固定正式 Client 与锁文件、Chrome Stable 公告/运行版本、JDK 17、三条本提交 CI run、隔离/清理回执。 |

只有以上必需场景和门禁均有同轮直接通过记录，才得出阶段 4 结论。Project 导出与 Feature 联合验收属于阶段 5；完整拒绝 Audit 和公开查询/导出属于阶段 6。任何缺项保持未执行或失败，不能因局部单元测试、旧轮次或聚合器结构校验通过而关闭 #240。
