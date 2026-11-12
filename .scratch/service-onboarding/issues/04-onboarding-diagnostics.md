# 04: 接入诊断与 Manifest 上报可观测

**What to build:** SDK 上报失败后不再只有一行日志：有限重试、后台补报、暴露上报状态，并按原因分类。平台聚合为只读诊断页，区分"配置已写 / 运行已加载 / 真实请求已验证"三级，直接指出缺项。不改变"iam 不可用不阻塞业务服务启动"的既有取舍。见[规格](../spec.md#acceptance)。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] SDK `ManifestReporter` / `ManifestAutoConfiguration`：上报失败按 3 次退避重试（连接与读超时保持 5s/10s），仍失败则保留启动成功并进入后台补报（有上限、可停止）
- [ ] 失败原因分类：网络不可达、401 凭证被拒、403 服务名不匹配（`manifest.serviceMismatch`）、409 版本冲突（`manifest.versionConflict`）、400 清单非法；分类结果进入状态，而不是只拼一段日志
- [ ] SDK 暴露只读 `/internal/onboarding-status`：已上报版本、最近成功时间、最近失败原因与时间、是否启用上报；不返回令牌与 iam 内部地址细节
- [ ] platform 聚合：经 `lb://` 拉各服务状态，新 Operator 权限码 `platform:onboarding:read`；服务不可达时只标记该服务不可用
- [ ] 三级语义：配置已写（平台侧检查 Nacos 路由与认证前缀是否存在）、运行已加载（服务自报状态）、真实请求已验证（只能由一次真实 HTTP 探测写入，禁止由配置推断）
- [ ] 控制台只读诊断页：按服务列出三级状态与缺失项，不提供任何写操作
- [ ] 测试：401/403/409/网络四类失败各自产生正确分类；iam 停止时服务仍正常启动且状态可读；无权限 Operator 403
- [ ] 文档：`docs/business-service-guide.md` 增加诊断入口与状态含义；`CHANGELOG.md` 记录 SDK 行为变化

## Comments

（暂无）
