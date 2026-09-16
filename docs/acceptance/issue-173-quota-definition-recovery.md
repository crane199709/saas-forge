# Issue #173：Quota Definition 创建、激活与复用

> **历史证据**：本文保留当时的验收记录与命令输出，不代表当前实现或当前门禁。其中的前端包名、界面描述与门禁计数可能属于已被 [ADR 0050](../adr/0050-consoles-adopt-soybean-element-plus.md) 替换的自建 Design System / React Shell 时期；当前 Vue 实现与验证入口见 [Console 设计规范](../25-design-system.md)、[Console 认证 Runtime](../28-console-authentication-runtime.md) 与 [测试基线](../console-testing-baseline.md)，复现按 [本地分层验证](../local-verification.md)。

## 结果

2026-09-12 本地实现、回归及真实 Chrome/Fresh Compose 验收完成。产品套件 **35/35 通过**，随后 Compose 重置及 Chrome 浏览器门禁通过，正式脚本退出码 0。通过的源码提交为 `652a788d775be167f2b55f392f217f947406390d`，启动时 tracked 工作区干净；后续提交仅更新文档与证据。

完整后端、完整前端门禁均已通过，最后后端改动另外完成 Entitlement 全量复验。最终远端 CI、验收清单与关闭状态见 [Issue #173 的完成记录](https://github.com/crane199709/saas-forge/issues/173)。

## 范围

对应 #173，父规格 #170；复用已关闭 #172 的恢复交互。保留全局唯一 `max_users`，不增加其他额度类型或 Plan 管理。

- 正式 `GET /api/v1/platform/quota-definitions` 按编码字面子串、状态及游标查询；详情从 Entitlement 权威读取。
- `quota-definition-operations` 提供当前操作者的列表、详情及原操作恢复。接口重新验证当前 Platform Admin 权限，其他操作者不能读取或恢复记录。
- 新增 V5 前向迁移。登记在独立事务保存；恢复互斥锁覆盖领域状态、原幂等结果、Outbox 与恢复结果的共同提交。未修改历史迁移。
- 原 actor、Key、命令种类和目标共同约束重放。24 小时边界后禁止重放，已提交结果仍可读取；没有稳定结果则显示 UNKNOWN，不能自动新建。
- 创建与激活均由共享类型化 Client 执行。Key 只存在 Runtime 内存 WeakMap，页面不构造或导入 Key，不持久保存请求或恢复材料。
- 独立列表、创建和详情页面采用共享组件及中英文文案。查询确认已有定义时直接复用；读取失败、权限不足、未决提交或非 DRAFT 状态不提供不适用的创建/激活动作。
- Tenant 与 Quota Definition 复用恢复展示组件，不建设跨领域工作流服务。

## 验证记录

本文件随实际验证结果更新；未执行项不视为通过。

- 红灯：新增服务恢复测试因恢复服务尚未存在而编译失败；Runtime 创建/激活两项恢复用例失败；独立页面恢复用例失败。
- 定向通过：Runtime 测试文件 51 项；新增页面文件 4 项；Entitlement Controller 5 项及 PostgreSQL 集成 16 项。数据库验证包括创建/激活稳定结果、真实写入拒绝后恢复、精确 24 小时边界、真实事务锁 PROCESSING、原 Key 拒绝、跨 actor 拒绝和游标作用域。
- 完整前端门禁首次失败于 lint，修复后再次失败于基线语言选择器已使用 `react-dom` 但未登记 peer/白名单；补齐已有依赖声明与锁文件。
- 完整后端门禁首次失败于路由总数仍为 32；新增 5 个正式 operation 后更新为 37，并验证其服务所有权与 USER_REQUIRED 认证要求。
- 初次产品预检使用旧证书，缺少 remote 域名且 443 被占用，未启动验收环境。用户停止 Edge 后，使用既有四域开发证书重新预检通过：Chrome 153.0.8010.36、域名、证书、443 与浏览器 TLS 导航。
- 完整后端 `./mvnw -Pbackend-local verify` 通过（6 分 25 秒），包括契约检查、Gateway、服务、SDK、Flyway/Testcontainers 与质量门禁。审查后恢复响应增加原激活目标 ID，执行 Entitlement 全量 `-pl services/entitlement-service -am verify` 复验通过，其中 PostgreSQL 集成 18 项。
- 最终 `pnpm --dir consoles run verify:workspace` 退出码 0：类型、lint、格式、边界、全部 workspace 测试、Chromium 组件/消费者/会话和生产构建通过。Runtime 127 项、Platform Console 21 项（额度页面 11 项）。新增路由导致两条旧路由断言失败，更新后定向复验通过；旧语言控件几何断言要求上下排列，改为任一轴不重叠，保留遮挡、会话和跨标签语言验证。
- 首轮 Fresh Chrome 产品运行 8/10 通过、2 项失败：脚本把数据库权限拒绝的 Gateway 响应错误预期为 500，实际为 502；受限服务日志确认 `permission denied for table quota_definitions`。网络拦截内抛断言导致父测试连带失败。改为拦截仅记录结果并完成请求，临时权限在 finally 恢复后再断言 Gateway 502；后续恢复仍严格要求 200 和原 Key。
- 第二轮在 `tls-ready` 失败，未进入产品测试：容器健康通过，但四域 Chrome 导航均为 ERR_CONNECTION_CLOSED，180 秒内未恢复；环境已自动清理。用相同证书、现有 Node 镜像和回环 443 的最小 TLS 容器复核，curl 及 Chrome 四域均为 200，探针已删除。未修改 TLS 配置或重启开发环境；该间歇连接故障原因未确定。
- 第三轮 TLS 通过，创建回滚后的原 Key 恢复成功；激活请求被 Gateway 以 403 拒绝。定位到原契约没有请求正文，生成客户端没有 JSON Content-Type。新增 Runtime 回归测试先失败（Content-Type 为 null），再通过正式契约声明可选空 JSON 正文并由类型化 Client 发送 `{}`，未放宽 Gateway。Entitlement 全量复验通过，Controller 同时覆盖 JSON 和原无正文调用。
- 修复后的完整前端门禁再次退出码 0（Runtime 128 项）。最后 Standards 复审发现生成 Java 未执行空正文约束，补充激活/恢复入口校验；非空对象、数组、标量不能触发业务，返回 400。Controller 红灯后，Entitlement 全量复验退出码 0，Controller 7 项、PostgreSQL 集成 18 项。
- 第四轮 #173 Chrome 子测试通过（约 7.9 秒），整套 14/22 通过、8 项失败。首个失败为旧 Tenant 用例在 390px 窗口直接等待导航，而当前 Shell 使用默认关闭的抽屉；后续串行用例因未完成语言/会话转换连带失败。更新脚本通过实际导航按钮打开、检查和关闭抽屉，保留 390px，品牌遮挡检查改为当前可见页头。
- 第五轮 #173 再次通过，整套 20/22 通过，仅品牌 Remote 用例及其父项失败：共享 Remote 验证器同样直接点击已关闭抽屉中的链接。补齐两个调用点的实际开导航步骤；审查要求的旧品牌消失断言也调整为抽屉打开时执行。
- 第六轮 #173 继续通过；20/22 通过，唯一品牌用例失败为旧断言要求紫色深浅主题不变。ADR 0042 和现有解析器生成独立主题 Token；原色 `#7C3AED` 对深背景 `#1A202A` 约 2.87:1，实际 `#8344EE` 约 3.14:1。保留精确 Token 检查，夹具按主题声明正确预期，未修改产品色彩或降低对比度。
- 第七轮在 TLS 就绪阶段连接重置，curl 同样返回 connection reset；未进入业务测试，隔离环境自动清理，未改变开发服务。
- 第八轮 32/35 通过，#173 与深浅主题均通过；唯一叶子失败是品牌并发回读辅助脚本的新窄窗口未打开抽屉，连带两个父项失败。补齐其初始与最新回读后的开导航步骤，保留旧响应晚到、Token/Context 与品牌隔离断言。
- 第九轮从 `652a788` 启动，所有 35 项产品测试通过，无失败/取消/跳过；Compose 重置及 `console-browser-chrome` 通过，退出码 0。受信 HTTPS → Gateway → Nacos → 真实服务链路完成；隔离环境已清理。

## 真实产品验收入口

`consoles/integration-test/quota-definition-acceptance.mjs` 已接入既有 Fresh Compose 产品套件。Browser plugin not available，沿用 Playwright 的 Chrome 产品渠道。

链路：真实 Chrome → 受信四域 HTTPS → Gateway → Nacos → IAM/Entitlement。通过生产页面创建、激活、复用，服务端提交后丢弃一次响应，刷新、浏览器关闭重启和重新登录后读取原结果；检查双击、编码/状态筛选、英文详情、无障碍、浏览器存储与页面错误。仅在隔离项目内临时拒绝数据库 INSERT/UPDATE、恢复权限，并在恢复提交后丢弃响应，不伪造成功响应。

## 独立审查

固定起点 `0f2bc00`。初审发现刷新后未决记录不参与新操作按钮判断（Spec P1）、恢复成功仍残留未知提示（Standards P2）、错误 Tenant 术语（Standards P3）及 ADR 0003 的到期默认规则需要明确例外。均已修复。后续审查发现空正文契约缺少入口校验、旧导航消失断言需要在抽屉打开时执行，也已修复。最终 `652a788` 两轴复审各 0 项剩余发现。

页面现在先遍历原操作者的相关恢复记录，未决、过期未知或读取失败均不开放新 Key。真实回滚恢复测试及页面测试覆盖 CREATE/ACTIVATE 的 NOT_COMMITTED、PROCESSING 和 UNKNOWN；已确认 COMMITTED 后清除本地未知提示。ADR 0045 与正式接口说明明确已登记 actor/Key 过期后不从原入口重建，保留可读取的稳定结果。

## 逐项验收

| #173 验收项 | 证据与结果 |
| --- | --- |
| 正式列表/详情、类型化 Client、独立页面及筛选/游标 | OpenAPI 与路由归属门禁、PostgreSQL HTTP/游标测试、Runtime 与页面测试通过；Chrome 编码/状态筛选返回 200。 |
| 全局唯一 max_users，已有复用，创建并激活 | 数据库唯一约束、并发和状态机测试通过；Chrome 创建 DRAFT、激活 ACTIVE、复用同一 ID，双击只发送一次。 |
| 持久事实恢复，原操作者/Key/24h，不因刷新新建 | PostgreSQL 真事务回滚/锁、actor 隔离、精确到期边界通过；Chrome 创建和激活在写入失败与提交成功但响应丢失后均继续原 Key，刷新/重启/重登读取同一结果。 |
| 权限不足、读取失败及动作限制 | HTTP 权限测试、跨 actor 恢复读取拒绝和页面 403/503 测试通过；未决、PROCESSING、UNKNOWN、读取失败或非 DRAFT 不开放不适用操作。授权隔离与 24h 由后端集成测试证明，未冒称浏览器覆盖。 |
| 真实浏览器及代表场景 | Chrome 153.0.8010.36，Fresh Compose 产品 35/35；包含响应丢失、重复操作、中文/英文、存储检查、无障碍及四域安全回归。 |

## 归档证据

- [本轮阶段与源码提交记录](assets/issue-173-chrome/acceptance-run.json)
- [创建响应丢失](assets/issue-173-chrome/issue-173-create-response-lost.png)
- [激活响应丢失](assets/issue-173-chrome/issue-173-activation-response-lost.png)
- [权威详情与两条已提交记录](assets/issue-173-chrome/issue-173-authoritative-detail.png)
- [英文详情](assets/issue-173-chrome/issue-173-english-detail.png)

截图已逐张检查。只归档安全阶段记录与页面截图，不上传原始服务日志、凭据或幂等 Key。本切片完成不代表 #170 或 #165 完成。


## 2026-09-12 关闭前原生环境复核

用户报告详情页两处 `NETWORK_UNAVAILABLE`。在用户原 Chrome 页面复现：`GET /api/v1/platform/quota-definition-operations`（含 `limit=100` 的自动检查）得到 502，浏览器因响应缺少 CORS 允许头而拒绝暴露结果。既有定义详情仍可读取。

只读检查确认本地 `entitlement_db` 的 Flyway 历史仅到 V4，恢复表不存在。正式 `entitlement-migrate info` 明确 V5 为 Pending；执行 `docker compose run --rm --no-deps entitlement-migrate migrate`，5 条历史/当前迁移校验成功，仅应用 V5，退出码 0。未修改历史迁移、删除数据、重启 IDE 服务或接管 Edge。

迁移后在同一 Chrome 页面分别重试，自动操作检查及手动操作记录读取均恢复，两个错误提示消失。当前操作者没有新恢复记录，显示“未找到操作记录；这不能证明先前请求未提交。”；9 月 4 日创建的原额度仍为 ACTIVE。此处证明已有数据库的前向升级与正式读取恢复，创建/激活/响应丢失场景仍由前述 Fresh 35/35 和服务集成测试证明。

补充[原生数据库升级步骤](../native-entitlement-development.md)，明确 IDE 应用账号不会代执行迁移。关闭前核验以最新推送提交的 Verify workflow 为准，运行链接和结论记录到 Issue，不以旧提交的 CI 代替。
