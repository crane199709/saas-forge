# Issue #184：中文身份与 Tenant 主链

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

关联 [Issue #184](https://github.com/crane199709/saas-forge/issues/184) 与父规格 [#183](https://github.com/crane199709/saas-forge/issues/183)。

> [!IMPORTANT]
> **证据时效边界**：本文全部绿色结果（含下文"第十八轮完整通过"）的基线是 `adaaf43c109067355e4153c4fe6d79476105393e`，它是 `8d4c570`（2026-09-15，切换正式业务页面至 Vue 并清除旧 UI）的**祖先**。该提交同时把 `consoles/integration-test/stage2-main-chain.mjs` 的选择器由 `.ant-select-dropdown` / `.sf-form-field` 改为 `.el-select-dropdown` / `.el-form-item`，并在提交信息中声明"真实后端、四域联调及 Fresh Compose 未执行"。
>
> 因此：**本文的通过结论只证明切换前的 React/Ant Design 控制台，不证明当前 Vue 3 + Element Plus 控制台。** 当前 Vue 制品与更新后的选择器组合尚无整轮 Stage-2 主链证据，重新验收属于 Issue #184 / #183 未完成的范围。不得据本文勾选或关闭相关 Issue。除这一时效边界外，下文记录的失败基线、第五至第十七轮问题定位与运维结论仍有效。

## 运行入口

```bash
export SF_ACCEPTANCE_TLS_CERT=/absolute/path/to/trusted-cert.pem
export SF_ACCEPTANCE_TLS_KEY=/absolute/path/to/trusted-key.pem
export SF_BRAND_EVIDENCE_DIRECTORY=/absolute/path/to/new-evidence-directory
bash scripts/verify-console-authentication-e2e.sh --stage2
```

已通过完整 Maven/workspace 验证、只修改验收脚本时，可用 `--stage2-product` 复用已构建制品重跑。它仍进行制品预检并创建全新项目和数据卷，但不声称重新运行 Maven/workspace 门禁。

入口复用既有 Fresh Compose 的随机项目、全新数据卷检查、受限凭据、基础引导、四域 TLS 就绪门禁和本轮清理。443 被占用时在构建和启动之前拒绝执行，由开发者释放入口；不停止或替换开发服务。执行完整 Maven/workspace 检查后构建本轮服务镜像；Tenant Console 挂载正式生产制品，退出 Remote/故障验收路由包装。

主链仅运行 Chrome、浏览器语言 zh-CN，无保存的语言偏好；不注入 localStorage。顺序为首次平台登录和改密 → max_users 与 Plan → 两个 Tenant、Subscription、同一 Tenant Administrator → 真实邮件设置密码 → 两个 Accessible Membership 的首次选择 → 切换与刷新 → Audit。所有业务创建和正常身份操作通过页面及原有共享 Client；没有 API 预建、写库种子或成功响应伪造。

Audit 观察只读本轮生产 Outbox 的事件引用与 Trace，再匹配 Audit Record 和消费记录。按操作时间、具体身份、资源、目标 Tenant/Membership 约束，逐事件最多等待 30 秒；必须已经发布并被消费，历史任意事件不能满足断言。

## 同轮复用与产物

`runStage2MainChain(consume)` 顺序完成主链后调用 `consume`，提供同轮 `runId`、浏览器页面、资源及内存凭据。后续安全/OAuth 切片应在此回调内执行，不得读取历史 JSON 预建前置或持久化凭据；回调失败使整轮失败，返回后销毁浏览器。独立 #184 入口不传回调即可验证本切片。

`acceptance-run.json` 记录编排阶段；`stage2-main-chain.json` 记录代码基线、工作区状态、运行标识、Chrome/JDK 版本、逐场景状态、非敏感资源引用、Audit 事件/Trace 关联和预期匿名拒绝。未执行阶段保持 `not-run`。匿名无会话刷新是明确的 401 场景，按页面、请求、场景、时间窗匹配；未知 HTTP/Console 错误、请求失败和未捕获异常阻断。主链不生成截图、录像、浏览器 trace、请求正文、Cookie、Token、密码或邮件链接证据。原始 Playwright 异常不传播，仅保留受控源码位置。

键盘登录、按钮提交、路由焦点、播报和安全存储沿用共享验收帮助函数；完整 Maven 的 Console 门禁保留国际化、无障碍与浏览器测试。

## 初次实现验证记录（释放 443 前）

- 基线：`f2883f5e59a7181a43da386c3ac968867dce8a00`；开始时仅有既有未跟踪 `.scratch/`，未纳入提交。
- 通过：Console 类型检查、本次脚本 ESLint/Prettier、Shell 语法检查；入口与诊断测试 20 项；Compose 布局校验覆盖 8 个独立应用、6 个验收组合以及正式 Tenant 制品挂载。
- 完整 `VITEST_MAX_WORKERS=2 ./mvnw --batch-mode --no-transfer-progress verify` 通过，用时 6 分 9 秒，包含后端、数据库集成、Console 类型/lint/格式/工作区测试/Chromium 与生产构建。日志 `/tmp/issue184-maven-verify.log`。后续只读版本记录与编排小修正另经 ESLint/Prettier、Shell 和入口测试复核。
- code-review 双轴复审通过：Standards 原 2 项（清理与未处理等待器拒绝）、Spec 原 1 项（Console 401 关联过宽）均已修复，无剩余阻断；审查不代替真实主链执行。
- 真实环境预检：Chrome `153.0.8010.37` 可启动；宿主 `127.0.0.1:443` 已有监听，Fresh 主链未执行。没有关闭开发入口，也没有将历史 #181 证据计入本轮。
- 新测试入口先因实现模块尚不存在而失败；真实边界的 green 尚待 Fresh 运行，不能将静态检查称为 TDD 主链通过。
- 远端 CI 和父规格聚合：未执行；Issue 与阶段清单不据此勾选或关闭。


## 2026-09-14：释放 443 后的真实复验

首轮基线 `9a073d990ae35e99bc943ba5a4f78a71a587194e`，完整 Maven/workspace 再次通过。真实启动暴露 overlay 的 `!override` 同时删除服务器脚本挂载；已改为按目标路径覆盖正式 `dist`，布局检查同步验证服务器及其依赖脚本仍在。

第二、三轮证明平台初始改密及新密码登录成功，但表单卸载对已收到 204 的无正文请求执行 abort。第六轮在真实邮件密码建立成功后观察到相同行为。报告仅对两个明确的密码接口，按同页面、同场景、同请求已收到 204、响应后一秒内且每接口最多一次记录 `completedResponseCancellations`；整轮仍须新密码登录成功。它们不是预期安全拒绝，也不放行其他请求失败或 Console 错误。

第四轮发现测试在套餐操作资格查询完成前键盘提交，未发出业务请求；已等待读取完成及按钮启用，保留键盘路径。第六轮的权益及两个 Tenant/Subscription/Administrator Initialization 均通过，但该轮因邮件密码写入后的取消尚未分类而整体失败。第五轮容器启动成功，宿主四域 HTTPS 全部 `ERR_CONNECTION_CLOSED`，在就绪门禁失败，未执行业务主链。所有失败轮次均清理本轮项目，不拼接为成功证据。

第七轮在首次 Membership 选择后发现验收读取了不存在的 JWT `sub`，已按正式签发契约读取 `identityId`；密码设置后返回登录页的匿名刷新也纳入该页面明确的匿名窗口。第八轮等待订阅按钮启用失败，第九、十轮进一步暴露选中值断言依赖 Ant 非固定 CSS 结构；已按 Plan 表单字段和精确显示文本验证选择，再等待按钮启用。第十一轮再次在宿主 HTTPS 门禁失败；容器内 TLS 可返回 HTTP 响应，宿主直连回环映射仍被重置，未执行产品主链。

第十二、十三轮确认 Plan 已选中且下拉关闭，失败来自同字段两份同名文本触发严格定位；修正为下拉隐藏后检查字段显示内容，并继续严格校验创建订阅返回的本轮 Plan ID。第十四轮通过此前所有场景及切换/刷新业务断言，但阶段末捕获切换请求收到 204 后的取消；新增该接口在 Tenant 页面、切换阶段、同请求 204 后一秒内且最多一次的精确记录，后续两次刷新及 Audit 仍必须通过。对切换取消仅记录观察事实，未确认其具体内部触发原因。

第十五轮主链 7 个场景全部通过，4 条生产事件均匹配 Audit，未知错误为零；随后 Chrome 消费者门禁在 Logo 可见后同步检查 `complete` 时失败，因此入口整体仍为失败。将该断言改为有界等待图片加载，保留 `naturalWidth > 0`；Chrome 消费者复验 39 项通过、2 项既有跳过。第十六轮因 `nacos-init` 退出 1 在启动阶段失败，未执行业务主链。

第十七轮在宿主四域 HTTPS 就绪门禁失败，未执行业务主链。停止反复创建环境后，独立执行完整 `pnpm --dir consoles run test:browser:chrome`，退出码 0：Design System 90 项通过、4 项跳过；消费者 39 项通过、2 项跳过；浏览器会话/Locale/Remote 8 项通过。跳过保持现有 Chrome 门禁配置，未删检查。日志 `/tmp/issue184-chrome-final.log`。

## 第十七轮结束时的结果与证据边界

| 验证 | 结果 |
| --- | --- |
| 第十五轮 Fresh 中文主链 | 7/7 场景通过，未知错误 0；两次 Tenant Created、Session Started、Tenant Context Switched 共 4 条生产事件与真实 Audit 消费记录匹配 |
| 运行身份 | `saas-forge-console-1789396697-41803-da9cbf`；Chrome `153.0.8010.37`；宿主 JDK `17.0.12`；五个服务 JRE `17.0.20` |
| 完整 Maven/workspace | 初次实现与释放 443 后第一轮均通过；最终仅改验收脚本/测试，未再次运行完整 Maven |
| 最终受影响检查 | Console 类型检查、ESLint/Prettier、Shell 语法、Compose 布局、入口与诊断 21 项通过 |
| 最终完整 Chrome 门禁 | 独立运行 137 项通过、6 项既有跳过，退出码 0 |
| 完整 Fresh 编排入口 | 尚无整轮全绿：第十五轮主链通过但旧 Logo 断言失败；修正后第十六、十七轮分别在 Nacos 初始化、HTTPS 门禁失败 |
| 远端 CI / 父规格 #183 聚合 | 未执行；不据本地结果勾选或关闭 Issue |

[主链脱敏报告](evidence/issue-184/stage2-main-chain.json)与[同轮编排报告](evidence/issue-184/acceptance-run.json)保留原始状态。证据基线为 `9a073d990ae35e99bc943ba5a4f78a71a587194e` 加报告列出的工作区修正；主链通过后未再修改主链实现。后续仅修复独立消费者测试的图片等待并补充文档，不能将其独立通过改写成第十五轮编排全绿。报告没有保存业务凭据、Cookie、Token 或邮件链接；既有 `.scratch/` 不纳入提交。

## 入口故障定位与第十八轮完整通过

HTTPS 已缩小到单个 Node TLS 容器，复用同一受信证书与 `127.0.0.1:443:8443` 映射，不启动业务服务：首组 8 次创建有 1 次宿主握手失败；再次捕获失败时，宿主 curl 退出 35，容器内 TLS 返回 200，仅重启该隔离容器后宿主正常证书校验返回 200。证据指向 Docker Desktop 宿主端口转发异常，未定位到 Docker 内部具体实现。原始受控观测在 `/tmp/issue184-tls-probe-final.log`。

入口现等待正常启动收敛；仅当四域连续检查至 15 秒后仍全部为连接关闭/重置时，才允许一次恢复。恢复前验证容器属于本轮随机 Compose 项目、服务为 `console-tls`，并在容器内核对实际证书指纹与挂载证书一致、四域 HTTP 均为 200。只重启这个容器，随后仍须 Chrome 正常校验证书链、域名和信任且四域全部 200。HTTP、证书错误和内部 TLS 失败不会触发恢复，恢复后持续失败仍阻断。恢复函数在真实隔离容器上另经证书指纹与宿主 HTTPS 检查；9 项回归检查覆盖单次恢复、恢复后失败及错误项目/服务拒绝，已接入 Console boundary 测试入口。

Nacos 的历史失败为工作负载配置读取未在 30 次尝试内成功。四次仅含 Nacos 与真实初始化客户端的 Fresh 复现均通过，初始化耗时约 2.8–4.4 秒，**尚未确认历史超时根因**。本次仅为最终失败增加数字响应码/固定分类，保持原权限、重试次数及非空配置条件；没有通过放宽超时掩盖问题。初始化回归 3 项通过，包含响应码可见但配置/凭据不可见；Nacos 配置校验、Shell 语法、相关入口检查及新增脚本 ESLint/Prettier 均通过。临时诊断脚本未纳入仓库。

第十八轮 `bash scripts/verify-console-authentication-e2e.sh --stage2-product` **退出码 0，完整产品与 Chrome 门禁通过**（适用边界见开头"证据时效边界"：该结果对应切换前的 React/Ant Design 控制台与旧选择器）：

- 项目 `saas-forge-console-1789399964-55969-1e8451`，全新卷；Nacos 初始化、四域 HTTPS、中文主链 7/7、4 条 Audit 关联、清理和最终 Chrome 门禁均通过，未知主链错误 0。
- HTTPS 报告 `recovered: false`，即本轮正常启动通过，未触发恢复；不能把这一轮称为实际故障恢复演练。
- 基线 `adaaf43c109067355e4153c4fe6d79476105393e` 加报告列出的工作区修正；运行期间其他任务提交了前端工具配置，按实际 SHA 记录。Chrome `153.0.8010.37`、宿主 JDK `17.0.12`，五个服务 JRE `17.0.20`。
- 本命令复用先前构建制品，未重新运行 Maven/workspace；远端 CI 与父规格 #183 聚合仍未执行，Issue 未据此关闭。

本轮独立证据：[完整编排](evidence/issue-184/r18/acceptance-run.json)、[中文主链与 Audit](evidence/issue-184/r18/stage2-main-chain.json)、[HTTPS 就绪](evidence/issue-184/r18/https-readiness.json)。保留此前失败报告，不将其状态改写为通过。
