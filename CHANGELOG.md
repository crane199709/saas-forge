# 变更日志

后端 Maven 制品与前端 npm 包使用同一语义版本。1.0 之前的次版本可包含不兼容变更，升级前请检查本节。

## 0.1.0

### 新增

- 对象存储支持 `StoragePurpose`：服务声明扩展名、大小与下载时长，SDK 统一校验并记录用途；IO 源文件、结果与订单附件分别限定用途，上传拒绝原因沿用前端 `userTip` 展示。

- SDK 新增资源恢复的共享错误码和中英文提示，`ListPage` 可选已删除视图及恢复事件；Business Service 接入指南补充恢复规则与验证要求。
- 角色、部门、岗位和 demo 订单支持查看已删除记录并恢复：保留角色配置，部门按当前父路径恢复，沿用原管理权限、租户与数据权限，以及现有唯一性规则。

- IAM 新增共享 Todo 状态索引与当前成员分页查询：按业务发生时间抵御重复、乱序事件，实时按数据库角色权限与 Plan 裁剪；demo-service 创建订单开启待办，关单、删单及超时关闭同步结束待办。
- IAM 新增按月分区的 Notification 收件箱，支持创建时 Permission 收件快照、分页、已读与未读计数，按 180 天整月清理；demo 订单创建事务发送真实站内通知。

- 新增 `forge-sdk-notification`：在业务事务内发送 Notification、开启和关闭 Todo，提供目标、站内链接与发生时间事件契约；经既有 Outbox 投递到 `forge-notification`，无需新增 SDK 表。

- 平台控制台新增任务运行入口，按服务查看规则、最近运行和问题运行；支持确认后手动触发与失败租户单独重跑，并提示历史留存边界、失败明细上限及运行冲突。

- 支持 Operator 按服务查看任务声明、调度规则和最近运行，异步手动触发或单租户重跑；复用运维令牌通道与读/管理权限，平台记录成功/失败审计。
- 定时任务自动在 Redis 保留最近运行与最近非成功运行，提供 `TaskRunHistory` 查询；记录租户结果、有界失败明细和安全错误摘要，并识别崩溃/失锁后的中断，无需数据库迁移。

- 新增 `forge-sdk-ops` 独立令牌运维通道，平台从 IAM 服务目录经负载均衡调用，并按服务标记运维能力可用状态。
- Dead Event 支持按服务分页查询、填写原因后直接重放或放弃；平台校验运维权限并记录成功/失败审计，接口只返回元数据。SDK 自动清理处置超过 90 天的记录，并随租户最终删除清理其死信。
- MQ 消费重试耗尽时独立事务捕获 Dead Event，按消费组与事件 ID 幂等保存；增加死信新增计数与 Outbox 未发送积压指标。已有服务升级时须添加 `forge_dead_event` 迁移，并保证 RocketMQ 消费组重试次数不小于 `forge.mq.max-delivery-attempts`（默认 16）。

- 首次交付 Forge BOM、按能力拆分的 SDK starter 与共享架构规则，Java 模块附带源码包。
- 发布 `@crane199709/sdk`、`@crane199709/ui`、`@crane199709/sub-app-kit`；提供类型声明与编译后的 CLI。
- 提供统一版本命令、标签发布质量门和临时 Maven/npm 仓库消费自检。
- 提供 Business Service archetype、接入指南和可在仓库外运行的 Sub-App 生成器；BOM 的 SDK 版本独立于业务工程版本。
- 提供四个后端服务与两个 Shell 的镜像构建入口，以及从临时发布到仓库外生成、启动和权限校验的模板冒烟质量门。

### 不兼容变更

- `ObjectStorage.prepareUpload`、`write`、`prepareDownload` 删除无用途重载，须以 `StoragePurpose` 为首参；导入与业务绑定前使用 `get(purpose, id)` 校验用途。`forge.storage.download-url-ttl` 删除，下载时长改由用途声明。已有服务须新增 Flyway 迁移复制 SDK `upgrade-purpose.sql`（demo V14）：旧对象回填 `unspecified`、新登记显式提供用途；旧对象须按可信业务关联归类后才可按明确用途下载。

- 引入 `forge-sdk-ops` 的服务必须配置独立 `forge.ops.token`；平台须配置服务令牌映射，漏配启动失败。本仓库本地启动须显式提供 IAM、platform 与 demo-service 的三个运维令牌环境变量，详见接入指南。

- Maven 版本从 `0.1.0-SNAPSHOT` 切换至 `0.1.0`，使用旧坐标的工程需更新 BOM 版本。
- 本版尚未承诺与其他版本的 Shell/Sub-App 共享依赖兼容；两端应使用同一版本。
