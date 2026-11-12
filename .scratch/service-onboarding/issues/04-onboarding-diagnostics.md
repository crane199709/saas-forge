# 04: 接入诊断与 Manifest 上报可观测

**What to build:** SDK 上报失败后不再只有一行日志：有限重试、后台补报、暴露上报状态，并按原因分类。平台聚合为只读诊断页，区分"配置已写 / 运行已加载 / 真实请求已验证"三级，直接指出缺项。不改变"iam 不可用不阻塞业务服务启动"的既有取舍。见[规格](../spec.md#acceptance)。

**Blocked by:** 01

**Status:** resolved

- [x] SDK `ManifestReporter` / `ManifestAutoConfiguration`：上报失败按 3 次退避重试（连接与读超时保持 5s/10s），仍失败则保留启动成功并进入后台补报（有上限、可停止）
- [x] 失败原因分类：网络不可达、401 凭证被拒、403 服务名不匹配（`manifest.serviceMismatch`）、409 版本冲突（`manifest.versionConflict`）、400 清单非法；分类结果进入状态，而不是只拼一段日志
- [x] SDK 暴露只读 `/internal/onboarding-status`：已上报版本、最近成功时间、最近失败原因与时间、是否启用上报；不返回令牌与 iam 内部地址细节
- [x] platform 聚合：经 `lb://` 拉各服务状态，新 Operator 权限码 `platform:onboarding:read`；服务不可达时只标记该服务不可用
- [x] 三级语义：配置已写（平台侧检查 Nacos 路由与认证前缀是否存在）、运行已加载（服务自报状态）、真实请求已验证（只能由一次真实 HTTP 探测写入，禁止由配置推断）
- [x] 控制台只读诊断页：按服务列出三级状态与缺失项，不提供任何写操作
- [x] 测试：401/403/409/网络四类失败各自产生正确分类；iam 停止时服务仍正常启动且状态可读；无权限 Operator 403
- [x] 文档：`docs/business-service-guide.md` 增加诊断入口与状态含义；`CHANGELOG.md` 记录 SDK 行为变化

## Comments

（暂无）

## 页面简报（按已批准规格）

平台 Operator 用此页定位服务接入缺项；主任务为逐服务比较配置、运行状态与真实请求三份独立证据。主操作只有“刷新”，没有写操作。首屏显示服务名、三级状态、具体缺项；上报版本、最近成功/失败时间与探测时间在展开行中查看。采用列表原型：PageHeader → PageToolbar → el-table，沿用权限导航。

测试 seams：真实HTTP上报失败分类和生命周期、Ops保护状态端点、平台真实HTTP权限/局部失败、外部ConfigService源与真实三级probe。Browser plugin not available，浏览器走查使用现有Playwright，1440/1024截图基线提交。

## Answer

已实现 SDK 结构化失败、异步有限重试/补报、只读内部状态；状态路径加入独立 Ops Token 链。初次 + 三次重试，退避 1/2/4 秒；随后每五分钟最多十二次，成功及关闭即停止。5/10 秒超时保留。IAM 200 HTML 也被拒绝为异常响应。

平台 `GET /onboarding` 使用专属 `platform:onboarding:read`；Nacos 客户端读取同 namespace/group 的 `forge-gateway.yaml`（前后 UP），候选取目录/路由/Ops配置并集。lb 实例状态与对明确 gateway origin 的 OpenAPI HTTP 探测分别取证。服务故障、目录故障、Nacos DOWN/读取中断均不伪造通过。HTTP 200 HTML 与错误服务身份均不通过。SDK OpenAPI 输出 `x-forge-service`。前端 `/onboarding` 按既有导航权限显示，只读刷新和展开详情；新增六张 macOS 1440/1024 基线并逐张查看。

具体命令、通过计数与边界见 [acceptance-04-diagnostics.md](../acceptance-04-diagnostics.md)。最后合入集成分支至 `c7e4b1`；模块全量交由集成分支统一执行，本票没有把未运行的模块全量计为通过。
