# 第 2 阶段同轮聚合运行

本说明对应 #189 的执行入口，不是验收通过记录。四项标准以 #183 的当前规格及统一 Console 决策为准。历史 #185/#187 的结果不能代替本轮结果。

环境准备方按 `docs/acceptance/independent-verification.md` 提供新的 Compose 项目、卷、JDK 17 服务镜像、受信 HTTPS Edge、真实邮件及非生产平台机制接收端，生成 `handoff.json`。前端由独立 `saas-forge-web` 运行；后端脚本不读取前端源码、不启动浏览器。个人配置和凭据文件不能提交。

同轮顺序为：部署引导 → 中文主链与英文 Tenant 路径 → Token/Redis 安全拒绝与恢复 → OAuth 创建、消费、丢响应恢复、时间到期与吊销 → Audit 和运行日志观察 → 前端聚合判定。Tenant 冻结使用额外 Tenant；OAuth 吊销、一次展示与恢复使用独立 Client，避免已撤销资源干扰后续场景。

环境方先启动两个控制器，参数均属于本次 handoff：

```sh
node scripts/token-security-redis-control.mjs "$HANDOFF" "$REDIS_CONTROL"
node scripts/oauth-overlap-time-control.mjs "$HANDOFF" "$OAUTH_CONTROL"
```

控制器限制独立项目和本轮资源；时间控制器验证原值、影响行数和恢复值，支持 24 小时重叠到期及十分钟恢复到期注入。它们不修改系统时钟，不能证明实际等待了这些时长。收到退出信号时恢复活动注入；环境方仍须核对控制器的恢复收据。

前端运行完成相应阶段、文件完整写出后，由环境方顺序生成观察产物：

```sh
node scripts/stage2-audit.mjs "$HANDOFF" "$AUDIT_INPUT" "$AUDIT_REPORT"
node scripts/stage2-runtime.mjs "$HANDOFF" "$SECURITY_REPORT" "$OAUTH_REPORT" "$RUNTIME_REPORT"
```

Audit 仅只读查询本轮 PostgreSQL，绑定浏览器观察到的身份、Session/Tenant、目标 Membership 和请求时间窗，关联已发布 Outbox、Audit 的源事件与 Trace。最多等待 60 秒；缺失、歧义或未发布均失败。`AUDIT_INPUT` 是受限中间文件，不能公开。

Runtime 扫描本轮六个服务在浏览器执行时间范围内的 ERROR。请求错误仅在 Redis 注入窗口、相同 Trace 的真实 503、精确异常类型与错误码均匹配时计为预期。定时撤销索引恢复没有 HTTP Trace，需绑定同一服务、注入窗口、完整 `recoverIfNeeded` 调用栈、Redis 连接/命令异常和同轮真实失败关闭请求；其他 ERROR（包括未知日志格式）继续阻断。报告只保留错误行散列及关联信息，原始服务日志不能当作脱敏产物。

前端聚合器会检查同一 runId、handoff 和报告散列，并拒绝缺失、失败、跳过、未执行及未知错误。每次必须使用新的输出目录，失败记录不得覆盖。完整 CI 需单独核对当前提交，不能以本机检查或旧 SHA 的 CI 代替。

“仅原操作者可恢复”需要第二名同等平台权限的真实操作者。若当前产品无授予入口，临时角色变更须先按仓库 AGENTS.md 获得明确授权，并限定本轮身份、保存原状态、精确恢复；缺少该前提时该场景为未执行，聚合不能通过。禁止为了通过验证直接预建业务资源或绕过此授权。

只有四项标准均有直接证据且错误判定通过，才能新增验收通过记录并勾选开发计划。失败轮次仍保留。结束后只停止本轮进程和容器；必须按项目标签再次检查运行容器，普通 `compose stop` 可能漏掉 profile 下的测试接收端。数据卷删除另行确认，不操作开发者环境。

本轮隔离验收显式配置 Redis 命令超时为 2 秒、IAM→Tenant Access gRPC 调用预算为 5 秒（使用准确具名通道 JSON 属性并核对绑定），以便真实 503 在 Console 的 8 秒请求预算内返回；这不修改仓库默认配置，也不能证明默认 Redis 60 秒、IAM→Tenant Access 3 秒配置下的浏览器反馈时序。临时角色写入前保存本轮身份原状态和预生成的精确角色 ID；回执未知仍按该 ID 重读恢复。只在 OAuth 报告有同轮终态且第二操作者已正式退出后完成回收，不能把中途进度文件存在当作完成。
