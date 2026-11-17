# 0.2.0 升级清单

`0.1.0` 与 `0.2.0` 均不是产品正式版。本文的版本表示制品坐标：已发布的旧输入是 `0.1.0`，发布准备使用固定提交的真实候选包，目标 `0.2.0` 发布之后才从目标仓库只读复验。不能用 `0.2.0-SNAPSHOT` 的版本文字或本机 install 缓存代替最终坐标证据。

## 选择起点与能力

| 工程实际起点 | 必须执行 |
| --- | --- |
| 已发布 0.1.0 Business Service | [服务升级清单](upgrades/business-service-0.2.0.md)，包括默认 SDK 表、构建检查、Ops、Bean Validation、规范与 Wrapper 差异 |
| 已发布 0.1.0 Sub-App | [前端升级清单](upgrading-sub-app-v020.md)，包括三包与共享版本、统一 `/api`、Vite 自定义项、主题、独立宿主及 Shell |
| 实际使用 storage | 另按 [storage 清单](upgrades/storage-0.2.0.md) 追加实际缺少的 purpose/retention，按可信业务引用归类，核对旧文件及生命周期 |
| 实际使用 IO | 另按 [IO 清单](upgrades/io-0.2.0.md) 追加缺少的三份增量与其 storage 依赖，核对历史任务、报告与新导入导出。0.1.0 没有 IO；专项旧输入为可核对历史源码的未发布构建 |
| 已有快照接入工程 | 先记录实际源码、依赖、原包和迁移历史，再按缺项合并；Lowcode/AI 试点不伪称已发布 0.1.0 工程 |

默认模板不增加 IO/storage。storage 传递引入的 scheduling 也需要 IAM 地址和服务凭据；直接使用 scheduling 的服务按自身已有任务验收。平台依赖、业务工程版本和 Resource Manifest 版本分别维护；仅换 SDK 时保留业务版本和 Manifest 声明版本。

## 合并与数据保留

1. 固定业务提交、Forge 来源与摘要、配置自定义项、Flyway 历史和验收环境；本次试跑使用隔离数据库与对象存储。
2. 从目标候选的实际 archetype/kit 在仓库外生成同名参数的参考工程，审阅差异。逐项合并父版本、SDK/UI/kit、必需依赖与配置、工具脚本、Agent 说明和规范副本；保留业务源码、页面、路由、业务标识、Maven profiles 和 Vite 覆盖。
3. 保留所有已执行迁移原文；只按实际缺项新增 Flyway 版本，不将新模板合并后的 V1/V2 覆盖旧历史，不运行 clean/repair。已有 purpose 不重放用途迁移；未知旧文件保持 unspecified，不能按文件名猜用途。
4. 后端先修 NeedBraces、原生中间件直引与格式红灯，再执行项目 verify。前端保留精确共享依赖并重新安装、格式/样式/类型/构建、业务回归与视觉走查。同名 `file:` 快照替换时刷新锁记录并核对安装内容；最终坐标使用实际 0.2.0 包。
5. 核对独立 Maven 缓存和可执行 JAR 内嵌 Forge 包、前端安装内容与候选摘要；平台运行包与 Shell/console 也来自同一固定版本。业务 SDK 消费缓存与平台运行包分开，避免源码 install 覆盖候选来源。
6. 运行业务自己的隔离产品验收、真实独立宿主和 Shell 挂载。更新浏览器的 `/api` 观察和 mock，并保持原业务路由；独立宿主的网关环境变量需显式传入 kit `gateway`，IAM 回调源必须匹配。

成功判据是构建检查、权限/租户边界、原数据/文件/历史保留、新功能、两宿主和截图均通过且来源一致。失败时先看日志和 HTTP 结果，补齐本工程差异；保持原数据库及迁移历史，另建证据目录重验受影响路径。

## 平台通用质量门与本地复现

需要 JDK17、Python3、Node24、pnpm12.4.2、Docker，先 `pnpm --dir frontend install --frozen-lockfile` 并安装 Chromium。读取实际旧 Maven 需要 `packages:read`；本地通过环境 `GITHUB_TOKEN`/`GH_TOKEN` 或既有 gh 登录提供，凭证只留在进程内存。npm 旧输入匿名读取。完整 Git 历史须包含 IO 固定历史提交 `d9f2b032ebae06000e476ba3fe22f4ff90e88a99`。

```sh
# 工作区干净时：一次真实候选构建，包含空制品消费与新建模板验收，随后四个升级场景顺序运行。
bash deploy/upgrade-smoke/run.sh /tmp/forge-upgrade-new
# 已有候选：先核对全部摘要，再在一个临时 registry 生命周期顺序运行同样四个升级场景。
bash deploy/upgrade-smoke/run.sh /tmp/forge-upgrade-repeat /path/to/fixed-candidate
```

可以用 `FORGE_UPGRADE_MAVEN_BASELINE` 指向已核对的旧 Maven 发布缓存、`FORGE_UPGRADE_NPM_BASELINE` 指向实际旧 npm 下载目录；不提供时只读下载并记录来源。所有目录必须新建且位于仓库外。`private/` 保留候选、工程、缓存与原始日志；`diagnostics/summary.json` 记录候选提交/版本/清单摘要、已通过场景及失败阶段。任何场景失败均使入口与 CI 失败，后续场景不运行。`diagnostics/` 只导出来源/结果、脱敏日志和场景截图；不上传原始 private、settings、npmrc、缓存、Trace ZIP/HTML 或运行凭据。完整 URL 被整体隐藏，原始诊断仅在本地排查。

CI 的 Template smoke 复用此入口，保留 Backend、Frontend、Main flow、Template smoke 四个发布质量门；publish/images 继续等待四门。验证 job 仅 `contents:read`、`packages:read`，读取 step 通过环境提供自动 token，包的 Actions 读取授权由仓库维护者按既有设置管理。未在本次任务配置外部授权；远端实际运行结果须另核对。只改升级脚本触发 Template smoke，升级/独立宿主浏览器辅助文件也触发其消费者。两个试点仍在各自仓库独立验收，整套业务测试不迁入平台 CI。

## 候选变化、配套切换与发布后复验

候选身份是干净源码提交、全部坐标、candidate.json 摘要与实际包摘要。开发分支继续 SNAPSHOT；最终 0.2.0 在独立版本树通过 `version.py set 0.2.0`、`check v0.2.0` 后形成干净固定提交，再真实构建，不能改旧包名或重写清单。与上一候选保存 `git diff`，按包/规范/运行包变化重新执行受影响的新建、四个升级与试点路径，收集各仓库最终 receipt。任何必要证据缺失或失败都保留未就绪状态。

同一环境协调更新平台服务、网关、Shell、SDK/UI/kit 和业务构建产物，准备备份、增量迁移、配置及回退安排；本清单不承诺跨版本混跑。候选验收不切换共享/生产环境。部署与制品发布由独立流程安排，新增数据库列的回退兼容性须按实际业务评估。

发布后按[工单 09](../.scratch/v020-upgrade/issues/09-published-package-consumption.md)进行只读复验：先核对目标 Maven/npm 已有 0.2.0、标签/提交与全部源码版本一致，再在该版本树执行 `bash scripts/release/publish.sh consume v0.2.0` 或 Verify published SDK。只读取、等待可用、隔离 Forge 缓存并消费，不重跑 deploy/publish、覆盖同版本或移动标签。保存实际下载摘要或构建对应关系，与最终候选逐项核对差异；上传成功和候选通过均不能替代该结果。09 不构成发布准备的前置，外部尚未发布时保留待执行。
