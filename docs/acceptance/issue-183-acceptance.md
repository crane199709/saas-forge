# Issue #183 验收复核

2026-09-30，复核结论：五项验收清单通过。本次复用 #189 已提交的同轮真实证据，执行离线重判并在线核对 CI；没有重新运行 Chrome、Fresh Compose 或完整 CI。经用户确认，GitHub 五项清单已勾选，[验收评论](https://github.com/crane199709/saas-forge/issues/183#issuecomment-5902494835)已发布，Issue 已以 `COMPLETED` 关闭。

## 依据与适用范围

- [Issue #183](https://github.com/crane199709/saas-forge/issues/183) 正文及 2026-09-29 的拓扑澄清：按统一 Console 的平台/租户工作上下文、统一 Origin 语言偏好和英文 `en` 验收，不恢复历史双 Console 架构。
- [固定聚合记录](https://github.com/crane199709/saas-forge/blob/a405a91580293d9648f85e169df1905efa9d5fd9/docs/acceptance/issue-189-stage2-aggregation.md) 与其同提交原始证据；仅采用 Run ID `c292ba74-804c-4b05-a351-1367edf19adb`，没有拼接 #208 或专项历史运行。
- 本机执行基线：后端 `40c9875d98ad0629e5e652c9ffc32b6b12c23391`、前端 `883c59e6d3698bc169366d012101ffa78e7bd21c`，两仓均 `dirty=true`；Chrome `154.0.8037.59`、JDK 17。不得把该运行描述为干净提交执行。
- 交付提交：后端 `a405a91580293d9648f85e169df1905efa9d5fd9`，前端 `7b243f21aeca412461c9e4b466973e9f2fab28c2`。本次复核前两仓工作区均干净，HEAD 与这两个提交一致。

## 逐项验收

| #183 清单 | 直接证据 | 结论 |
| --- | --- | --- |
| 全新环境主链及三类 Audit 关联 | [security.json](assets/issue-189-stage2/security.json) 的 bootstrap、Tenant switch/refresh；[audit.json](assets/issue-189-stage2/audit.json) 的 Session Started、Tenant Created、Tenant Context Switched，绑定同轮操作者、资源、Trace 和源事件 | 通过 |
| Token、Refresh 重放、Redis、越权、冻结/解冻与页面恢复 | security 的 10 项场景全部通过；[runtime.json](assets/issue-189-stage2/runtime.json) 将唯一预期服务错误关联至 Redis 注入，未知错误为 0 | 通过 |
| OAuth 一次展示、恢复边界、重叠窗口、实际消费与吊销 | [oauth.json](assets/issue-189-stage2/oauth.json) 的 16 项检查全部通过，包含其他操作者/重复/超时恢复拒绝、窗口不延长、Scope 拒绝、吊销后的新签发及既有 Token 拒绝 | 通过 |
| 默认中文主链、英文代表操作及偏好/输入持久化 | security 的 `locale-english-input-persistence-and-identity-path`，以及 OAuth 中英文真实重叠拒绝；按统一 Origin 的当前产品语义判定 | 通过 |
| 基线、版本、隔离标识、场景状态、脱敏及恢复/清理 | [aggregate.json](assets/issue-189-stage2/aggregate.json)、[execution.json](assets/issue-189-stage2/execution.json)、[时间注入](assets/issue-189-stage2/time-injections.json)、[角色恢复](assets/issue-189-stage2/role-restored.json)、[cleanup.json](assets/issue-189-stage2/cleanup.json) | 通过 |

## 本次实际执行的验证

- `SHA256SUMS` 中 12 个文件全部校验通过。
- 使用前端交付提交的 `scripts/stage2-aggregate.mjs` 对上述 manifest 离线重判：`passed`，退出码 0。
- 前端 `tests/stage2-aggregate.test.ts`：11 项通过，0 失败、跳过；缺项、失败、跳过、未知错误、异轮及来源不匹配均阻断成功。
- 在线核对[后端 Verify](https://github.com/crane199709/saas-forge/actions/runs/36655253715)：head SHA 为上述后端交付提交，Nacos、Fresh-volume E2E、JDK 17 backend/contracts 三项任务全部成功。
- 在线核对[前端 verification](https://github.com/crane199709/saas-forge-web/actions/runs/36655266896)：head SHA 为上述前端交付提交，verify 与生产 UI/无障碍/视觉回归两项任务全部成功。失败时才上传的诊断产物步骤被跳过，不是验收场景跳过。

上述 CI 结果补充 #189 记录提交时的“尚未推送、CI 未执行”状态；不据此声称本验收文档自身通过 CI。[开发计划](../16-mvp-development-plan.md) 的四项浏览器清单已经勾选，其 CI 待核对文字应以本次精确 SHA 复核结果补充。

## 保留边界

24 小时与十分钟到期采用受保护状态注入，未实际等待对应时长；服务消费属于非生产平台机制验收。隔离运行使用 Redis 2 秒超时、IAM→Tenant Access gRPC 5 秒预算，不扩展为默认超时条件下的反馈时序结论。专用测试角色、Redis 与时间注入已恢复，任务容器停止、数据卷保留。第十一轮停滞根因仍未稳定复现，最终通过不等于已查明根因。整个 MVP 和后续阶段不在本次完成结论内。
