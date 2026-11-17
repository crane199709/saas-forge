# 工单 09：发布后只读消费待执行记录

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
