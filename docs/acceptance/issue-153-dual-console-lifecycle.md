# Issue #153：双 Console 本地生命周期验收

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

日期：2026-09-08。需求：[Issue #153](https://github.com/crane199709/saas-forge/issues/153)，父需求 #148；开始时 #151、#152 均为 CLOSED。

本轮完成开发文档与可执行的实机验收，**未完成双 Browser Session Slot 的完整隔离验收**：用户已手动登录 Platform，并确认目前没有 Tenant 账号。不能据此关闭 #153。本报告不改写 #126/#131 的历史范围，也不将先前 Platform 证据用作本次 Tenant 生命周期证据。

## 环境与边界

- macOS、Docker Desktop，Node 24.14.1、pnpm 11.22.0，仓库已有 Playwright Chromium。
- 未提供 Browser 技能，使用 Playwright；浏览器上下文显式设置 `ignoreHTTPSErrors: false`。交互浏览器视口为 1360 × 900。
- 正式 Origin：`https://platform.saas.forge.test`、`https://console.saas.forge.test`、`https://api.saas.forge.test`；两个 Console 在同一浏览器上下文中运行。
- 开始时 Platform/Tenant 均 STOPPED，Edge RUNNING。受限沙箱最初报告 Edge UNAVAILABLE，获得 Docker 只读访问后确认其为 RUNNING，因此不将最初输出判为环境故障。
- 五个后端均为 CONTAINER、READY、Nacos 实例数 1。只操作受管前端和当前项目 Edge；未运行 setup、bootstrap、后端 replace/restore、数据库或凭据维护。
- Password Setup 只使用内存中新生成、从未签发的随机无效 Challenge 和临时表单值，不消费有效链接、不修改现有密码。报告不保存 Cookie、Token、密码或敏感响应体。

## 需求与结果

| 验收项                        | 结果与证据                                                                                                                                                                |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 中英文 Console / Compose 文档 | 四份 README 对齐：三 Host、固定端口、setup 授权、九种显式命令、独立日志、六种状态与恢复、共享 Edge、动态 Gateway、前台调试差异及验收恢复                                  |
| 页面身份与有意义内容          | 正常 TLS 下标题分别为 SaaS Forge Platform Console / SaaS Forge Tenant Console；Platform 登录后显示 Platform 总览，Tenant 显示登录表单；未见框架覆盖层                     |
| 实际 API 请求                 | 两页首次分别向 API Origin 提交 POST `/api/v1/auth/refresh`，无会话时均返回 401；Platform 手动登录并刷新后同一路径返回 200，仍显示已认证总览                               |
| 双槽位刷新与登出隔离          | **BLOCKED**：Tenant 无现有账号，不能验证双侧认证、双向登出和刷新互不干扰；未尝试创建账号或重置凭据                                                                        |
| Password Setup 文档与资源     | Tenant Origin 的 GET `/password-setup`、`/password-setup/app.js`、`/password-setup/styles.css` 均为 200，类型分别为 text/html、text/javascript、text/css                  |
| Password Setup 实际提交       | 点击正式页面“设置密码”按钮，POST `/api/v1/auth/password-setups` 返回 400、application/problem+json，页面显示链接无效错误；证明经过 Gateway 的拒绝路径，不代表成功设置密码 |
| 双 WSS / HMR                  | 连接分别为 `wss://platform.saas.forge.test`、`wss://console.saas.forge.test`；在各自 app.tsx 临时加入可见标记，浏览器分别观察到更新，无需重建镜像；文件均已还原             |
| loopback 监听                 | `lsof -nP -iTCP:5173 -iTCP:5174 -sTCP:LISTEN` 显示两个 Node 监听仅为 127.0.0.1                                                                                            |
| Edge 到宿主 Vite              | 在原 Edge 容器内通过 Node HTTP，以各自受控 Host 访问 `host.docker.internal:5173/5174`，均返回 200                                                                         |
| LAN 隔离                      | 对宿主当时 en 接口 IPv4 地址直连 5173、5174，均为 ECONNREFUSED；未放宽监听地址                                                                                            |
| 生命周期                      | 实测 Platform-only、Tenant-only、all、单目标停止、重复 start/stop、聚合 status；另一 Console 运行时停止 Platform 保留 Edge，stop all 后三个目标均 STOPPED                 |

## 浏览器诊断与证据限制

- 首次交互浏览器出现两条 404 console 记录，未捕获到对应资源路径；后续干净上下文用 CDP 复核未重现。不能宣称首次 console 完全无错，也不据此臆测为某个资源故障。
- 无会话的 refresh 401 是登录页恢复探测的实际失败响应，已与 Platform 登录后 refresh 200 区分。
- 第一轮将标记插入 main.tsx，触发 React 重复 createRoot 警告；还原入口后改用 app.tsx 组件标记完成双 HMR 复核。该入口编辑方式的警告不作为产品修复依据。
- 辅助验收脚本使用中文标签定位时超时；改成语言无关的密码输入框后，两页均得到 `LOGIN_VISIBLE`，错误覆盖层数量均为 0。Password Setup 的 200/400 证据在辅助脚本后续定位超时前已完成。
- Edge 内首次使用 fetch 的 Host 覆盖探测返回 403；改用 Node HTTP 明确设置 Host 后两端均为 200。未更改 Vite Host allowlist。
- 本轮只验证桌面 Chromium，没有新增跨浏览器、移动端或 Tenant 认证成功证据。

## 自动化验证

| 检查                                               | 结果                                                                                                                                                              |
| -------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `node --test scripts/test/*.test.mjs`              | 47/47 通过                                                                                                                                                        |
| `corepack pnpm --dir consoles run test`            | 边界测试 90/90，包测试 266/266 通过                                                                                                                               |
| `corepack pnpm --dir consoles run typecheck`       | 通过                                                                                                                                                              |
| `corepack pnpm --dir consoles run lint`            | 通过                                                                                                                                                              |
| `corepack pnpm --dir consoles run format:check`    | 通过                                                                                                                                                              |
| `corepack pnpm --dir consoles run build:workspace` | 通过；Vite 保留 chunk size 提示                                                                                                                                   |
| Shell / JavaScript 语法                            | local-development.sh、local-https-development.sh 的 bash -n，以及 frontend-lifecycle.mjs、local-development.mjs、local-https-development.mjs 的 node --check 通过 |
| `git diff --check`                                 | 通过                                                                                                                                                              |

首次沙箱测试因禁止本地监听出现 EPERM；允许回环监听后重新运行上述脚本及 Console 测试，均通过。该环境失败不是行为测试的 TDD red。本轮最终只修改文档，没有为文案添加实现耦合测试，也没有修改产品代码；运行的是已确认公共边界的既有测试和实机验收。

## 环境恢复

恢复辅助调用第一次遗漏 Edge ensure 所需的回调，在已启动 Edge 后抛出 TypeError；随后先核对容器身份，再传入回调复核，未盲目重新创建资源。所有临时 HMR 源码已经逐字还原；只有四份 README 和本报告属于本轮交付。原始本地脱敏运行记录与截图位于 `/tmp/issue-153/`，该临时目录不作为跨机器可复现的永久证据。验收结束时未发布 GitHub 评论、勾选或关闭 Issue；Git 提交由后续显式授权单独执行。

最终只读复核通过：Platform STOPPED、Tenant STOPPED、原 Edge 容器 RUNNING，与初始运行组合一致。完全停止后的 `start all` 也使三个目标恢复 RUNNING，再 `stop all` 成功。五个后端仍全部为 CONTAINER / READY / Nacos 1，容器 ID 与初始一致，`docker ps` 运行时长从约 7 小时持续累计至 7–8 小时；本轮未重启后端。初始记录使用容器运行时长而非精确 StartedAt，因此不声称完成精确时间戳比对。Edge 经验收启停，其启动时间发生预期变化，但容器身份未变。
