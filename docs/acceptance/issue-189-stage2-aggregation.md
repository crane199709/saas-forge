# Issue #189：第 2 阶段同轮聚合验收

2026-09-30，本机第十二轮 **Fresh Compose + 真实 Chrome 聚合通过**。四项浏览器标准均有本轮直接证据，最终进程退出码为 0。当前提交尚未推送，远端完整 CI **未执行**；本记录不关闭或修改父 #183，也不代表整个 MVP 完成。

## 本轮来源与条件

- Run ID：`c292ba74-804c-4b05-a351-1367edf19adb`。浏览器执行时间为 UTC `01:17:55`～`01:20:14`（北京时间 `09:17:55`～`09:20:14`）。
- 后端基线 `40c9875d98ad0629e5e652c9ffc32b6b12c23391`，前端基线 `883c59e6d3698bc169366d012101ffa78e7bd21c`，两仓执行时均为 `dirty=true`，包含本次待提交修正。不是干净提交的 CI 结果。
- Chrome `154.0.8037.59`、JDK 17；共享 Client `@crane199709/saas-forge-api-client@0.4.0`，来源提交 `ebff4b338d0c4b39e51488ac1e9b98885318d438`。源码、锁文件、驱动散列及镜像 ID 保存在原始脱敏报告中。
- 独立项目 `sf-acceptance-c292ba74-804c-4b05-a351-1367edf19adb`，新建专属 PostgreSQL、Redis、Kafka 数据卷与网络。复用同一批已构建应用镜像，没有复用业务数据。部署引导之外的权益、Tenant、管理员、Password Setup、Subscription、OAuth Client 均经正式 Console 和真实邮件建立。
- 沿用统一 Console 的受控四域 HTTPS 拓扑与正式类型化 Client；产品路径使用 Console，攻击、独立服务消费和故障观察按父规格单独执行。非生产平台机制 Receiver 不代表生产 Runtime 已交付。
- 隔离环境 Redis 命令超时为 2 秒，IAM→Tenant Access 具名 gRPC 通道预算为 5 秒；已核对容器变量与同镜像依赖的属性绑定。未修改仓库默认值，不声称默认 Redis 60 秒、gRPC 3 秒下的反馈时序通过。见 [运行条件](assets/issue-189-stage2/runtime-conditions.json)。

## 四项浏览器标准

| 标准 | 同轮直接证据与结论 |
| --- | --- |
| 中文完整主链及 Audit | [安全/主链报告](assets/issue-189-stage2/security.json) 的 `fresh-console-bootstrap-two-tenants-and-real-mail-password-setup`、`same-identity-tenant-switch-and-refresh`：平台登录、首次改密、最小权益、Tenant/Subscription、真实邮件 Password Setup、管理员登录、Membership 选择、Tenant 切换及刷新均通过。[Audit 报告](assets/issue-189-stage2/audit.json) 精确关联 `SESSION_STARTED`、`TENANT_CREATED`、`TENANT_CONTEXT_SWITCHED` 与本轮页面资源、时间窗、操作者、源事件、Trace 和已发布 Outbox；不是仅按事件类型计数。 |
| 全部安全拒绝与恢复 | 同一 [报告](assets/issue-189-stage2/security.json) 共 10 项通过：错误 Token、越权 Tenant、Refresh 重放及未过期 Token 撤销、Console 隐藏受保护内容、Redis fail-closed 和恢复后权威探针/页面登录、Tenant 冻结及解冻后旧凭据仍拒绝、新登录恢复。冻结使用独立 Tenant；解冻等待目标 DELETE 200 后才继续，Redis 恢复后再进入 OAuth。 |
| OAuth 管理与实际服务消费 | [OAuth 报告](assets/issue-189-stage2/oauth.json) 共 16 项通过：Secret 一次展示与不可重复读取，刷新/离页/跨标签失效清除，真实创建与轮换响应丢失后的原操作者恢复，第二名同平台权限且不同 `identityId` 操作者拒绝、重复恢复拒绝、十分钟到期拒绝；24 小时重叠窗口保持、到期旧 Secret 拒绝而新 Secret 可用、吊销后新签发及既有未过期 Token 拒绝、Scope 不足拒绝。均包含真实 Gateway/Nacos/Receiver/Starter 消费。独立 Client 隔离吊销与恢复状态。 |
| Locale 与英文代表路径 | [安全/主链报告](assets/issue-189-stage2/security.json) 的 `locale-english-input-persistence-and-identity-path` 验证切换 Locale 后输入保留、没有额外业务写入，以及英文登录和 Tenant 路径；[OAuth 报告](assets/issue-189-stage2/oauth.json) 另验证真实中英文重叠拒绝。默认中文完整链执行一次。 |

OAuth 详情页及键盘创建/关闭检查通过，产品根节点无障碍扫描 0 项违规；这是该场景的自动扫描结论，不扩展为全部页面的人工视觉或无障碍认证。相关类型、契约、国际化与安全检查结果见下表。

## 错误、注入与清理

[Runtime 报告](assets/issue-189-stage2/runtime.json) 扫描同一浏览器时间窗的六个服务：1 条 ERROR 精确关联到 Redis 注入期间的真实失败关闭请求；未知 Runtime 错误为 0。两份浏览器报告的未知错误和非预期请求均为 0。预期拒绝绑定场景、请求与时间，未全局忽略 401/403/503。

[聚合报告](assets/issue-189-stage2/aggregate.json) 与 [执行报告](assets/issue-189-stage2/execution.json) 均通过，三个子阶段退出码均为 0。判定器 11 项 CLI 测试验证缺项、失败、跳过、未知错误及过期来源关联会退出失败；历史第十、十一轮也实际传播为最终失败，见 [失败轮次](../audits/issue-189/failed-rounds.json)。本轮没有拼接历史成功结果。

[时间注入回执](assets/issue-189-stage2/time-injections.json) 记录 24 小时重叠到期和十分钟恢复到期的精确状态注入，两次注入、两次恢复各影响 1 行并核对原值。**未实际等待 24 小时或 10 分钟**，也未修改系统时钟。Redis 停止及恢复窗口由主链报告记录。

经用户明确授权，对本轮 UI/邮件建立的专用第二身份临时授予平台角色；写入前保存原状态及精确 ID，结束后经正式页面退出并回收。OAuth 报告确认退出通过，[角色恢复回执](assets/issue-189-stage2/role-restored.json) 确认精确回收 1 行且恢复到原状态。只停止本轮进程和容器；[清理记录](assets/issue-189-stage2/cleanup.json) 核对十二个本任务项目均无运行容器，数据卷保留，未操作其他项目。

## 验证状态与复核

| 验证 | 状态与范围 |
| --- | --- |
| 本轮 Chrome 聚合 | 通过：主链/安全 10、OAuth 16、Audit 3、Runtime 1；无跳过或未执行场景 |
| JDK 17 完整 Maven `verify` | 通过：726 项，0 失败/错误/跳过，12 分 20 秒；在当前 Java 改动的干净源码快照运行，避免忽略目录中的历史代码副本干扰结构扫描 |
| 后端 Node 工具 | 65 项通过；新增 Trace/Redis 精确分类正反例，保留未知格式 ERROR；Java Trace 回归与 Refresh 异常分类均先失败后通过 |
| 前端类型、Lint、Locale、全部 Node 测试 | 通过，234 项测试，包含 11 项聚合判定 CLI 测试；构建通过。早期完整 verify 的 postbuild 曾受沙箱 IPC 限制，单独重跑通过，原失败事实见诊断记录 |
| 双路代码复核 | 规范及规格复核均完成，发现的问题已修正；不替代执行证据 |
| 当前提交完整远端 CI | **未执行**：尚未推送；没有用旧 SHA 的 CI 代替。仍需推送后核对两仓当前提交的既有完整门禁 |

第十一轮 OAuth 停滞原因未稳定复现；阶段进度和响应体等待上限改善了诊断，独立诊断及本轮通过不证明该停滞根因已修复。前十一轮事实完整保留于 [诊断记录](../audits/issue-189-progress.md) 与其脱敏摘要。

证据从同轮报告逐字复制，未重写状态或时间；发布前检查已知凭据、Token、Cookie、密码及本机临时路径未进入产物。[SHA256SUMS](assets/issue-189-stage2/SHA256SUMS) 可核对原报告字节。进入证据目录后，以前端仓库的聚合器执行以下命令可离线重判；本轮重判退出 0：

```sh
node "$FRONTEND_REPO/scripts/stage2-aggregate.mjs" manifest.json /absolute/new-result.json
shasum -a 256 -c SHA256SUMS
```

执行入口与职责见 [运行说明](../agents/stage2-aggregation.md)。本地临时配置、原始日志和凭据不属于交付物，不能作为长期证据引用。
