# #206：独立验证与环境交接记录

日期：2026-09-28。完成两仓交接工具、检查入口和责任/历史文档；没有修改或关闭父 #201。实现不新增业务 API、不变更数据库迁移或认证边界。

## 实现与证据范围

- 后端 `scripts/acceptance-handoff.mjs` 提供 prepare、ready、attach、probe、correlate。Fresh 准备与实际启动由环境方显式分步执行；前端不接管环境。关联器拒绝轮次/摘要/时段不一致及空、失败、跳过报告，退出非零且不自动清理。
- 前端新增 `verify`、`lint:check`、`verify:handoff`、`verify:browser`；保留已有 Runtime/业务/Remote 入口，迁入旧精确格式化和调用方协议门禁。CI 保留类型、Lint、全部 Node 测试和构建的失败传播。
- 责任映射与待迁独有覆盖见 [交接说明](independent-verification.md)。#183–#189、#103 仍由各票完成，有限冒烟不代表业务聚合/完整视觉及无障碍覆盖。
- [历史策略](console-history-reproduction.md) 实测重建 113 个相关提交；末端 Console 树与原始提交一致，bundle 完整性验证通过，不覆盖新仓库 main。

## 通过

| 类型 | 结果 |
| --- | --- |
| 后端完整工具测试集 | 62/62，零跳过；含 5 项新增 CLI 行为测试。最初沙箱回环端口 EPERM 的轮次不计通过，允许本机监听后重跑通过 |
| 前端全部 Node 测试 | 216/216，零跳过；新增交接输入、会话调用方和精确格式化覆盖 |
| 前端类型、Lint、生产构建 | 通过；Lint 最初仅 package.json 排序警告，已按仓库格式修正 |
| 生成 Fresh overlay 的 Compose 模型 | 真实 docker compose config 解析通过；路径、标签、唯一镜像与仅 Gateway 随机回环端口检查通过；未启动容器 |
| Fresh 交接工具的受控进程测试 | 通过合法轮次及跨项目网络、数据卷未挂载等拒绝；Docker/curl 在该测试中为外部进程替身，不是实机 Fresh 证明 |
| 真实后端探针 | 本轮公开 JWKS 匹配、精确 Console Origin 允许、不可信 Origin 返回 403 且不含允许头 |
| 真实 Chrome | 154.0.8037.57，Playwright 1.62.1，1440×1000，受信 HTTPS、证书校验开启；先核验页面提供的源码/lock/Client 身份与运行脚本目录一致，再真实登录→显式平台上下文→刷新→第二标签页恢复→页面退出后刷新匿名；Cookie Secure/HttpOnly/host-only，标题焦点与键盘提交通过 |
| 登录展示 | 中英文、主题切换与匿名截图人工核对；不是像素基线自动比较或完整无障碍认证 |
| 同轮关联 | runId `aa8d11c9-513b-4782-94e2-7dc514fd86eb`；两类报告绑定同一 handoff SHA-256，关联器通过；作用域 `handoff-smoke-only` |

真实凭据读取用户指定的 0600 文件，不进入参数值或产物；独立 Chrome 会话在页面退出后关闭。仅启动本任务的前端 Vite，后端/HTTPS Edge/数据均未替换或清理。Browser 插件未提供，因此使用已有外部 Playwright 运行时；本票未擅自增加依赖。

持久脱敏原始证据：[交接](assets/issue-206/handoff.json)、[后端](assets/issue-206/backend.json)、[浏览器](assets/issue-206/browser.json)、[关联摘要](assets/issue-206/result.json)。前端仓库另存匿名登录截图。第一次浏览器执行因语言按钮定位器同时匹配包装元素与内部按钮失败；修正为明确可访问名称后重跑成功，不把失败轮次计入通过。

## 版本组合与限制

- 后端源工作区基线 `da4567c859b200d375b4eed6d69e9d557cf60048`，前端源基线 `75a812ccbf14610cf4ff169d15ed648ed922b7d2`；执行时两仓 dirty=true，包括本次未提交实现，后端还保留原有 `.dockerignore` 修改。
- 正式 Client `@crane199709/saas-forge-api-client@0.4.0`，包内源提交 `ebff4b338d0c4b39e51488ac1e9b98885318d438`、dirty=false。npm registry 核实 SHA-512 为 `6tNE9pA5ljT08NRLh3IHdWdUg9AqT8utzysJQNgXGCCsNARtx+kM5wTm+qaL6jSWjnyqQXS04Ymjb9veuBJJUA==`，与前端 lock 一致。
- 本轮是真实**已有环境**，不是 Fresh。现有原生服务没有可信部署 Git 身份，因此记录为源工作区版本，未把源 SHA 冒充部署制品版本。Fresh ready 另有镜像 ID、源码标签和 JDK 17 校验。
- 未执行：真实 Fresh 启动/销毁、#183–#189 完整聚合、Token/Redis 故障注入、业务主链、完整视觉/自动无障碍矩阵、新提交远端 CI、实际新版本发布。完整 Maven 未重跑：本次只改 Node 交接工具和文档，后端完整工具套件及前端全套检查为相关验证；既有 Java/迁移/契约 CI 未删减。
- 新 Client 没有发布，也未要求前端与后端同时发布。当前成功只能证明这份 Client 与已启动环境在交接冒烟范围内兼容，不能推出任意历史组合兼容。

#206 不据此自动关闭；特别是运行制品身份的完整核实和真实 Fresh 准备仍缺实机证据。交接实现可供后续专项直接使用，缺口必须保持可见。

## 审查

Standards / Spec 双轴审查完成：修复前端运行页面未绑定来源的问题，增加 dev/build HTML 来源摘要并在凭据提交前核对；同时修复未暂存源码删除导致摘要生成失败的回归，独立临时 Git 仓库测试通过。复审未发现其他阻断代码问题。Spec 保留两项实机证据缺口：真实 Fresh、后端运行制品身份；不能据此关闭 #206。
