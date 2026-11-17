# 工单 09：发布后只读消费验收

Status: resolved

2026-11-17 08:14 UTC，实际已发布的 `0.2.0` 通过仓库外只读消费；08:15 UTC 完成实际归档与候选的对应核对。[工单 09](issues/09-published-package-consumption.md)的六项验收全部满足，父规格随之收口。下方另保留 05:51 UTC 的发布前探测历史，不再代表当前制品状态。

## 实际发布输入

- GitHub 标签 [`v0.2.0`](https://github.com/crane199709/saas-forge/tree/v0.2.0)，源码提交 `10b8f30d82fcb036b550bc55a8a2540fc7576f46`；annotated tag 对象 `0ae2a779d88074e04388591896b4875a641d7019`。标签发布后没有移动或重建。
- Maven 来源：`https://maven.pkg.github.com/crane199709/saas-forge`，全部 Forge 坐标 `0.2.0`。实际下载 22 个 POM 和 38 个 JAR（含 19 个源码 JAR）。
- npm 来源：`https://registry.npmjs.org/`，`@crane199709/sdk`、`@crane199709/ui`、`@crane199709/sub-app-kit` 均为 `0.2.0`。元数据和 tarball 均可下载，三个 tarball 的 npm integrity 逐项通过。
- 逐文件来源、大小、完整 SHA-256、候选摘要、归档内差异和工作流对应关系已保存为 [published-v020-provenance.json](published-v020-provenance.json)，共 63 个实际制品。实际下载窗口 `08:12:18—08:15:38 UTC`。

## 发布与只读复验分别核对

| 流程 | 结果与边界 |
| --- | --- |
| [原始发布 44035010646](https://github.com/crane199709/saas-forge/actions/runs/44035010646) | 版本检查及四个质量门全部成功，六个镜像发布及按 digest 回拉成功。第一次发布尝试 npm 身份预检 E401，未上传 Maven/npm；第二次 Maven 发布成功，随后 npm EOTP。因此原始流水线整体仍显示 failure，不能描述为全绿。 |
| [npm 专用恢复 44043705141](https://github.com/crane199709/saas-forge/actions/runs/44043705141) | 用户再次更新现有 Secret 后成功。固定 checkout 原标签 `10b8f30d`，仅补发缺失的三个 npm 版本并等待安装元数据及 tarball 可读；没有重复 Maven deploy、推送镜像或移动标签。 |
| [独立只读消费 44044837346](https://github.com/crane199709/saas-forge/actions/runs/44044837346) | `Verify published SDK` 在 `v0.2.0 / 10b8f30d` 上执行成功；08:14:06 UTC 输出“制品消费自检通过：0.2.0”。 |

只读入口复用 `scripts/release/publish.sh consume v0.2.0`，先严格核对源码版本；GitHub 权限为 `contents:read`、`packages:read`。临时 consumer 位于仓库外，Maven 使用独立本地仓库，复制第三方缓存时排除整个 `io/saasforge`；Forge 制品实际从目标仓库解析。npm consumer 使用精确的三个 `0.2.0` 依赖，无 workspace 或 reactor 替代输入。

消费作业的 Maven BOM、全部 SDK 与 architecture 依赖解析输出 BUILD SUCCESS；npm 安装 154 个包成功，随后 `tsc`、实际 Vite 配置工厂加载、`forge-sub-app-lint` 均通过。lint 输出“Sub-App 隔离约定检查通过（1 个文件）”。该消费作业没有进入 build/pack/deploy/publish 或镜像入口。

## 候选与实际发布的对应及差异

候选来源为 `9f61c8a0073c82a900abeff45ce21c5600b6afb3 / 0.2.0`，`candidate.json` SHA-256 为 `ceed5ff8789ffd51f5727e3861c63f2ae90c4750c7cbc387a991b979d98adc94`。对候选与发布源码逐路径执行 git diff，`backend`、`frontend/apps`、`frontend/packages`、前端根 package/lock/workspace 及 `docs/standards` 无差异。发布前补齐的测试收集及升级安装 CI 修复不进入这些打包输入。

| 实际制品 | 与候选核对结果 |
| --- | --- |
| 22 个 Maven POM | 全部逐字节一致。 |
| 3 个 npm tarball | 全部逐字节一致，且实际下载完整性验证通过。SDK SHA-256 `89d39aec9091502911c45b734e6875314bbae7f305076e1a7c6f32a1d75eb041`；UI `0b6b43a30a9e557ff4934544a0f656b6d3ede134355204b8ec11e0a795a9d2a8`；kit `77002bcd4cea8f4bdc7b8545ce6979345d2cb06b54ffd785b03c713d0d19f580`。 |
| 19 个源码 JAR | 归档字节不同，全部归档内文件的名称和内容摘要一致，无新增、删除或修改的源文件。 |
| 19 个其余 JAR | 归档字节不同；其中 9 个归档内所有文件一致，另 10 个归档共 23 个 class 内容不同，其他文件一致，无新增或删除文件。差异类及摘要逐项列在 JSON 中；`javap -public -s -constants` 对这 23 个类的公开 API 核对全部一致。 |

归档时间戳确有差异，例如 core JAR 的首条目候选为 `2026-11-17 13:00:28`、实际发布为 `2026-11-17 07:32:14`，两者 Manifest 均为 Maven JAR Plugin 3.4.2 / Build-Jdk-Spec 17。反汇编可见字符串拼接处 `String.valueOf` 与 invokedynamic 参数、随后的指令偏移不同；不将它们描述为相同字节码，也不凭版本字符串断言内容一致。源码、资源、POM 和公开 API 的对应已核对，发布源码另有四个标签质量门以及本次实际只读消费成功作为证据。

## 执行材料与验证边界

持久核对清单见上述仓库 JSON；CI 日志由各工作流链接定位。原始执行材料位于 `/private/tmp/v020-release-20261117/`：`consume-success.log`、`npm-recovery-success.log`、`npm-recovery/v020-npm-publication.json`、`published-provenance.json`、`published-artifacts/`、`class-comparison/` 和 `images-publication.json`。npm 恢复归档 ID `14310897686`，ZIP SHA-256 `4112d94cac58315ea0242998dc9e7b6e17fc6d186e26929597593cd0f6d8159e`。

此次消费与归档下载仅使用读取操作，令牌未写入仓库，Maven 认证仅发送至固定官方 origin，跨 origin 重定向不携带认证；保存的清单没有临时下载签名或认证头。上传和消费成功分别记录，既有版本未覆盖，发布标签未移动。

本票证明最终仓库制品可供仓库外工程消费；没有重新执行两个试点的全部业务产品测试，也没有执行共享/生产数据迁移或运行环境切换。发布前的新建、增量升级、IO/storage 与试点验收继续引用 [08 验收](acceptance-08.md)。Lowcode 原有三份未提交文件的内容和模式逐一核对保留，业务仓库未由此次发布操作推送。

## 历史：发布前待执行记录（05:51 UTC）

记录时间：2026-11-17 05:51 UTC。工单：[09](issues/09-published-package-consumption.md)。结果为 **待执行 / needs-info**，没有通过发布后消费验收。

01—08 均已 resolved，08 已合入集成 `cc7748d69d2e0c51f5a6fcfd079642ab0b5a41f4`；发布前结果见 [08 验收](acceptance-08.md)。本次尚未取得 09 外部发布前置已满足的证据，只保存只读探测事实，不代为发布，也不提前勾选消费验收项。

## 目标、输入与检查结果

- 目标标签：`crane199709/saas-forge` 的 `v0.2.0`。
- Maven：`https://maven.pkg.github.com/crane199709/saas-forge`，目标版本 `0.2.0`；按候选清单检查 22 个 POM，覆盖 BOM、父 POM、SDK 聚合 POM、全部 SDK、architecture 和 archetype。
- npm：`https://registry.npmjs.org/`；`@crane199709/sdk`、`@crane199709/ui`、`@crane199709/sub-app-kit` 的精确版本 `0.2.0`。
- 已验收临时候选源码 `9f61c8a0073c82a900abeff45ce21c5600b6afb3`、版本 `0.2.0`、249 个文件；`candidate.json` SHA-256 `ceed5ff8789ffd51f5727e3861c63f2ae90c4750c7cbc387a991b979d98adc94`。它仍是临时候选，不是外部已发布输入。

| 前置 | 本次只读结果 | 验证边界 |
| --- | --- | --- |
| 发布标签和源码 | GitHub 插件可读取公开仓库元信息；精确 `v0.2.0` ref 和 release API 404，matching-refs 返回 `[]`；`git ls-remote --exit-code origin` 的精确标签查询退出 2、空输出。结果于 05:29:18 UTC 封存。 | 没有可核对的发布标签提交，未对发布源码运行版本检查。 |
| npm 三包 | 05:30:17—05:30:30 UTC，三个精确 `0.2.0` 版本元数据 GET 均 404。 | 未取得目标版本的元数据或 tarball，未执行安装、类型、Vite 或 lint CLI 消费。 |
| Maven 必需 POM | 同一 HTTP 窗口内，固定官方 origin、携带读取凭据的 22 个 `0.2.0` POM GET 均 404。 | 当前读取身份下不可用；未取得目标 POM/JAR，未执行 BOM/SDK 解析。 |
| Maven 读取正对照 | 05:32:21—05:32:25 UTC，用同一内存凭据单次 GET 已发布的 `forge-parent:0.1.0`，返回 302。响应 SHA-256 `cddd7fc64e20f6a9867638ec30a6b44f42e30da95c3d047050268945743b1f9f`。 | 此次禁止跟随重定向，未取得最终 POM 200，仍保留认证/最终可见性不确定；不能据此宣称 Maven 消费成功。 |

标签缺失与 npm 三包 404 已独立说明检查时点的外部前置未满足。Maven 首轮未认证 401 只反映认证边界，不能据其推断未发布；后续取得内存凭据后才得到表中的 404。以上结果不推断检查时点之后的仓库状态。

## 执行材料与核对

原始材料在 `/private/tmp/v020-upgrade/09-published-check/`：

- `github-plugin.json`：GitHub 插件结果和 git 精确 refs 查询的退出码、输出。
- `http-preflight.json` 与对应 `*.response`：25 个 HTTP 请求的来源、逐项 UTC 时间、状态、响应头、正文长度和完整 SHA-256。按原始 JSON 计数为 22 个 Maven 请求、3 个 npm 请求；响应正文摘要已逐项核对一致。
- `maven-positive-control.json`：单独保存的 `0.1.0` 正对照，未混入目标版本的 404 计数。
- `unauthenticated-first-attempt/`：保留首轮未认证探测及其边界。
- `check.py`、`README.md`：探测过程与范围。读取 token 仅捕获到进程内存，仅发送至固定官方 Maven origin；跨 origin 重定向剥离认证。材料未保存 token 或认证头，GitHub token 形态扫描无命中。

本次只改跟踪与交接文档，相关验证是上述只读前置探测、响应摘要/计数核对和文档差异检查；没有新增测试或运行重型验收。

## 待执行及未覆盖范围

独立发布完成后，先刷新标签、提交、版本和目标仓库状态，在匹配的全部 `0.2.0` 源码树执行严格版本检查，再按 [09 交接](handoff-09.md) 运行既有 `publish.sh consume v0.2.0`，隔离 Forge 本机缓存并保存实际 Maven/npm 来源、完整摘要与消费日志。没有读取到目标归档，因此尚未验证发布制品与候选的源码/构建对应关系或字节差异。

本次没有运行 consume、Maven 依赖解析、npm 安装或消费检查，没有 deploy、publish、推送、创建/移动标签或切换环境。工单 09 保持 `needs-info`，所有验收项仍待满足；该等待不阻塞已经完成的发布前准备，也不替代升级或部署验收。
