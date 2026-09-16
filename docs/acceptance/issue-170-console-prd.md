# Issue #170：Console 前三项 PRD 验收汇总

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

核对日期：2026-09-13。范围为 [Issue #170](https://github.com/crane199709/saas-forge/issues/170) 的 35 个用户故事及八项验收，复核 GitHub 子任务正文/完成评论、仓库验收记录、产品验收脚本和当前提交 CI。#171～#178 均为 CLOSED，清单全部完成。未发现该 PRD 范围内剩余验收缺口。

## 逐项核对

| PRD 验收项 | 用户故事 | 对应证据与结果 |
| --- | --- | --- |
| 权威读取、Accessible Memberships 复用、授权、游标与错误语义 | 1、7～11、15、18、32 | [#171](issue-171-current-session.md)、[#172](issue-172-tenant-creation-recovery.md)、[#173](issue-173-quota-definition-recovery.md)、[#174](issue-174-plan-management.md)、[#175](issue-175-subscription.md)、[#178](issue-178-oauth-client-reads.md)：正式契约、共享 Client、独立资源页面及 HTTP/数据库/真实浏览器验证通过。OAuth Client 不返回 Secret。 |
| Platform 认证与恢复 | 2～6 | #171：Fresh 首次改密、登录、Refresh、Logout；原生 Chrome 重启恢复；IAM HTTP 凭据错误/登录保护，Runtime 会话失效与故障反馈通过。Current Session 不保证未来 Refresh。 |
| 最小权益、订阅、初始化产品路径 | 9、11、16、17、19、31 | #172～#176：生产 Console 经受信 HTTPS/Gateway/Nacos 到真实服务完成全链路。未提交表单离开保护、双击和未知结果限制有浏览器/页面回归。 |
| 新 max_users 最小 1、历史 0 与真实耗尽 | 12～14、35 | #174/#175：新请求前后端一致拒绝 0，新激活/订阅拒绝历史 0；历史 Plan、权益和原操作响应保持可读、不改值。生命周期 Fresh 13/13 覆盖合法正额度耗尽拒绝及释放归零，兼容门禁精确处理 minimum 收紧。 |
| 初始化、通知、Subscription、Quota 分别权威读取 | 18、20～23 | [#176](issue-176-administrator-initialization.md)、[#177](issue-177-password-setup-notification.md)、#175：历史初始 Membership、订阅状态/有效期和实际用量分别读取；真实 SMTP 失败不回滚激活或额度，合法重发不重复初始化/扣额，邮件服务接受不冒称收件箱送达。 |
| 原操作者持久恢复、期限及隔离 | 24～29 | #172～#177：响应丢失、刷新/重启/重登、原 actor/Key/指纹恢复；普通 24 小时边界及 UNKNOWN 不自动新建；初始化持久根超过 24 小时仍可读取，补偿后明确 RETRY_REQUIRED 才新尝试。跨 actor 404、当前权限撤销 403，其他管理员只能查看业务进度。 |
| 局部故障、双语、共享交互及安全 | 30、31、33 | #171～#178：失败区域保留已确认信息、可重试并禁用依赖未知状态的动作；迟到响应隔离、无敏感浏览器持久化、双语、焦点、无障碍和四域会话检查通过。 |
| 真实浏览器/Fresh 记录及开发计划 | 34 | 各子项保留历史失败、修正和最终通过记录；#178 本机最终真实 Chrome 产品 40/40，失败/取消/跳过为 0；当前 SHA CI 与产物复核见下。开发计划仅勾选本 PRD 的前三项。 |

## 当前提交 CI 复核

- 验证提交：`ed9b49dbb6bad52d9d11b8a3c88df1c617d9f408`，与核对开始时本地 HEAD 一致，工作区干净。
- [Verify 34747247761](https://github.com/crane199709/saas-forge/actions/runs/34747247761)：completed / success。Nacos configuration and permissions、Tenant lifecycle fresh-volume E2E、JDK 17 and Console authentication / JDK 17, Fresh Compose and trusted TLS (Chrome) 三项均成功。
- 已下载 `four-domain-browser-evidence-ed9b49dbb6bad52d9d11b8a3c88df1c617d9f408` 并核验 `acceptance-run.json`：commit 与上述 SHA 一致，dirty=false、mode=fresh-compose、target=ci、scope=--product、status=passed；全部阶段（含 product-chrome、compose-reset、console-browser-chrome）及 Chrome 渠道 passed。本机下载位置 `/tmp/issue170-ci-evidence` 为临时目录，长期证据以 CI 产物为准。
- 当前产品脚本包含 Tenant、Quota Definition、Plan、Subscription、初始化、通知及 OAuth Client 的真实产品验收调用，并保留原认证和浏览器安全检查。

本次没有修改产品代码或重新运行本机重型验收，使用上述已完成的当前提交 CI 和各切片直接证据。授权、24 小时精确边界等部分证据来自服务集成测试；部分浏览器 403/503/响应丢失场景为明确的故障注入，不冒称全部是基础设施真实中断。子文档中的“远端 CI 未执行/Issue 未关闭”为撰写时历史状态，其后完成事实由各 Issue 的关闭评论及本次当前 SHA CI 补充。

本次更新开发计划、规格状态及本汇总文档；这些文档更新不属于上述 CI 已验证的提交。仅完成 #170，不自动完成或关闭 #165、#88，也不将 Tenant Console 后续路径或 OAuth Client 完整凭据管理计入已完成范围。
