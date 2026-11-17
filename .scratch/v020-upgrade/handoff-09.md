# 09：目标仓库只读消费交接

08 的候选与新建/升级验收属于发布准备；09 等独立发布流程完成后执行。此次没有上传 Maven/npm 制品、创建或移动 `v0.2.0` 标签、切换共享或生产环境。

## 固定输入

- 最终临时候选源码：`9f61c8a0073c82a900abeff45ce21c5600b6afb3`，全部 Forge/npm 坐标 `0.2.0`；独立版本树 `/Users/liuhuan/.codex/worktrees/v020-release-candidate/saas-forge`，其版本提交不合回 SNAPSHOT 集成分支。
- 实际候选 `/private/tmp/v020-upgrade/candidate-v020-9f61c8a0`，249 文件；`candidate.json` SHA-256 `ceed5ff8789ffd51f5727e3861c63f2ae90c4750c7cbc387a991b979d98adc94`。按清单逐文件核对；原包不改名或重打版本。
- 三 npm 原包 SHA-256：SDK `89d39aec9091502911c45b734e6875314bbae7f305076e1a7c6f32a1d75eb041`；UI `0b6b43a30a9e557ff4934544a0f656b6d3ede134355204b8ec11e0a795a9d2a8`；kit `77002bcd4cea8f4bdc7b8545ce6979345d2cb06b54ffd785b03c713d0d19f580`。
- 新旧候选源码差异、38 个 Maven 归档及三 npm 归档内容比较：`/private/tmp/v020-upgrade/08-candidate-impact/`。试点与通用场景最终结果由 [08 验收](acceptance-08.md) 汇总；不能用原 `54115992 / 0.2.0-SNAPSHOT` 阶段替代。
- 目标 Maven：`https://maven.pkg.github.com/crane199709/saas-forge`；npm：`https://registry.npmjs.org/`，包名 `@crane199709/sdk`、`@crane199709/ui`、`@crane199709/sub-app-kit`。

真正发布应从包含 08 最终门禁修复与整体验收审查结果的源码，按既有版本流程形成全部坐标为 0.2.0 的提交。`9f61c8a0` 是本地候选验收来源，不是已发布标签；最终发布来源与其不同时，按 `08-final-source-diff/` 核对后续差异和打包输入，保留诊断修复，重新判断任何制品源码变化。

## 外部前置及执行

已有只读预检 `/private/tmp/v020-upgrade/published-v020-preflight.json` 的时间为 `2026-11-17T04:24:56.354113+00:00`：当时三个 npm `0.2.0` 均 404。这只证明该时点 npm 前置未满足，没有据此推断 Maven 状态，也没有代为发布。09 保持待执行，开始时重新核对实际发布状态。

最新认证只读预检在 `2026-11-17 05:30:17-05:30:30 UTC` 完成，证据 `/private/tmp/v020-upgrade/09-published-check/http-preflight.json` 与 `github-plugin.json`：22个必要Maven POM和三个npm 0.2.0均404；远端精确标签API404、matching为空，`git ls-remote` 退出2且输出为空。没有运行consume或发布。此前未认证401不作为未发布证据；本轮结果仍只对应检查时点，09开始时按实际外部状态执行。

1. 记录实际发布标签、提交与时间；所选源码全部坐标须为 `0.2.0`。`python3 scripts/release/version.py check v0.2.0` 必须通过；不能在 SNAPSHOT 树上放宽检查。候选本地版本提交没有发布标签，真正发布来源若不同，先比较源码和制品对应关系。
2. 只给读取权限 `contents:read`、`packages:read`，凭证通过内存环境变量传给进程，Maven server id `forge-release`。使用已有仓库配置，不新增发布授权或写权限。
3. 在该匹配版本树运行 `bash scripts/release/publish.sh consume v0.2.0`，或手动 Verify published SDK 并选择匹配源码 ref、输入 `v0.2.0`。入口等待 npm 安装元数据和 tarball 可读，再以独立 Forge 缓存执行 Maven BOM/SDK 解析、npm 三包安装、类型、Vite 入口与 lint CLI 消费。consumer 位于仓库外。
4. 保存实际仓库/下载来源、版本、完整 SHA-256、消费日志与结果；逐项对应候选清单。相同字节直接记录；因重新构建、归档时间戳或版本元数据造成差异时，比较归档内文件、源码提交与构建配置并明确差异，不能仅比版本字符串。若有实质差异，按影响更新验收。
5. 消费失败时保留具体下载/权限/类型等诊断并修正消费接缝；不重新 deploy/publish、不覆盖同版本或移动标签。CI 材料按 08 的脱敏边界导出，不上传 token、随机密码、预签名 URL 或私有原始配置。

09 只证明最终仓库制品可供仓库外消费，不替代已有数据升级、完整业务产品或部署切换验收。独立发布上传成功与只读消费成功分别记录。
