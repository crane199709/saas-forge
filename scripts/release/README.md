# SDK 版本与发布

在仓库根目录执行：

```sh
python3 scripts/release/version.py set 0.2.0
python3 scripts/release/version.py check v0.2.0
# 打标签后立即进入下一个开发版本
python3 scripts/release/version.py set 0.3.0-SNAPSHOT
python3 scripts/release/version.py dev
```

命令同步后端版本（`backend/.mvn/maven.config` 中的 `-Drevision`）、frontend 根目录与 packages、apps 的版本，以及 `@crane199709/sdk` 源码中的 `sdkRuntime` 版本；不改第三方版本。

后端版本只维护在 `backend/.mvn/maven.config` 一处：各 POM 的 Forge 坐标与 BOM 的 `forge.version` 一律写 `${revision}`，改版本不需要改 POM。`flatten-maven-plugin`（`resolveCiFriendliesOnly`）在安装与发布时把 `${revision}` 替换为实际版本，仓库外工程解析到的是普通版本号。`check` / `dev` 同时检查 POM 中没有写死的 Forge 版本。

分支上的源码始终是 `-SNAPSHOT` 开发版本，同一个正式版本号只对应一份发布内容。发布步骤：

1. `set <正式版本>`，更新根目录 CHANGELOG.md（单列不兼容变更），提交并打标签 `v<正式版本>`。
2. 紧接着 `set <下一版本>-SNAPSHOT` 并提交，再一起推送分支与标签。分支 CI（Backend quality）执行 `version.py dev`，源码不是统一的 SNAPSHOT 版本即失败；标签构建跳过这一检查。

发布标签必须为 `v<语义版本>`，拒绝 SNAPSHOT、标签与文件不一致。`publish.sh local`（含模板冒烟）以 `check --allow-snapshot` 接受当前 SNAPSHOT 源码，部署到临时仓库；Maven 开发快照使用下节独立入口。

## SDK 开发快照与每日兼容验证

`bash scripts/release/snapshot.sh local` 把当前统一的 `-SNAPSHOT` Maven 制品发布到临时 file 仓库，再在仓库外创建消费工程，排除本机全部 `io.saasforge` 缓存后解析实际制品。契约测试验证 `TenantPlanChanged.tokenQuota`、历史消息默认不限，以及 AI Manifest 的使用/发布/回滚动作。不发布 npm 包、不创建正式标签。

`bash scripts/release/snapshot.sh remote` 发布到当前 GitHub 仓库的 Maven Packages，并执行相同的仓库外消费。使用上表的 `MAVEN_RELEASE_URL`、`MAVEN_RELEASE_USERNAME`、`MAVEN_RELEASE_PASSWORD` 及 `GITHUB_REPOSITORY`；目标 URL 必须与当前仓库一致，凭证不写入文件。开发快照版本可持续更新，消费方使用 `-U` 拉取新构建。

`SDK snapshot integration` 工作流在相关 main 推送、手动触发和每日北京时间 10:00（UTC 02:00）执行。先复用完整 Backend quality，成功后用仅发布 job 持有的 `packages: write` 发布快照并验证外部消费；发布或消费失败均使工作流失败。工作流随本次提交推送后才会在 GitHub 生效。

`ai-platform` 建仓后配置仓库地址 `https://maven.pkg.github.com/crane199709/saas-forge`、server id `forge-release`、有 `read:packages` 的读取凭证，以及启用 snapshots/updatePolicy=always；依赖 `io.saasforge:forge-dependencies:0.2.0-SNAPSHOT` 与所需 starter，Java CI 通过 `./mvnw -U verify` 验证。AI 仓库的每日完整产品集成构建由其建仓工单落地；平台侧外部契约消费只验证 SDK 公共接入面。

标签工作流复用后端验证（含 archetype 生成测试）、前端完整检查与 mock E2E、真实主链路 E2E 和模板冒烟；所有质量门通过后才能发布。镜像工作流也依赖这些质量门；配置 `IMAGE_PREFIX` 时推送 GHCR 并验证拉取，未配置时只构建，参见 [镜像说明](../../deploy/images/README.md)。

Maven 发布包含 forge-dependencies、forge-parent、forge-sdk 聚合 POM、全部 forge-sdk-*、forge-architecture 与 forge-service-archetype；父 POM 是仓库外解析继承链所必需的。平台服务和 demo-service 设置 deploy/source skip。目标 URL 通过 `-Dforge.release.url` 注入，凭证对应 Maven server id `forge-release`。

npm 先构建再使用 `pnpm pack`，由 pnpm 将 `workspace:` 替换为实际版本并应用 `publishConfig`，最后发布同一 tarball。`sub-app-kit` 的 Vite 配置和生成器携带当前 Shell 的共享依赖版本快照，CLI 发布为 JavaScript，可在仓库外生成完整工程，参见 [Sub-App 指南](../../docs/sub-app.md)。

## 临时发布验证

需要 JDK 17、Python 3、Node 24、pnpm 12.4.2 与 Docker，先执行 `pnpm --dir frontend install --frozen-lockfile`。本地复现：

```sh
scripts/release/publish.sh local v0.2.0-SNAPSHOT  # 版本取当前源码
```

完整模板冒烟执行 `bash deploy/template-smoke/run.sh`：在上述临时仓库存活期间生成仓库外服务和 Sub-App，运行构建与测试，并在新建隔离环境中验证 Manifest 上报、网关权限和内部接口拒绝。环境与日志说明见 [Business Service 接入指南](../../docs/business-service-guide.md)。

只在新建的临时目录发布 Maven file 仓库，并在绑定 127.0.0.1 的临时 Verdaccio 容器中发布 npm 包。容器退出时清理，目录保留供排查。空 Maven 工程只导入 BOM 并声明 SDK/架构模块，不设内部依赖版本，使用独立 Maven 缓存解析（仅复用本机第三方缓存，排除全部 `io.saasforge`）；空 npm 工程安装三个包、编译类型使用示例、加载 Vite 配置并执行编译后的 lint CLI。验证服务制品不存在且 Java 模块附源码包。首次运行需要下载 Docker 镜像与 Maven/npm 依赖。

## 正式仓库配置

已选定 Maven 使用当前 GitHub 仓库的 GitHub Packages，npm 使用 npmjs.org。配置如下：

| 配置 | 来源 | 值 |
| --- | --- | --- |
| `MAVEN_RELEASE_URL` | GitHub Variable | `https://maven.pkg.github.com/<小写 owner>/<小写 repo>`，必须对应当前仓库 |
| `NPM_RELEASE_REGISTRY` | GitHub Variable | `https://registry.npmjs.org/`（可省略尾斜杠） |
| `NPM_RELEASE_ACCESS` | GitHub Variable | 必填，`public` 或 `restricted`，由维护者明确选择 |
| `NPM_RELEASE_TOKEN` | GitHub Secret | 有目标 scope/package 发布权限的 npm token |
| `NPM_RELEASE_TAG` | GitHub Variable | 可选，默认 `latest`，预发布建议 `next` |

Maven 用户名来自 `github.actor`，密码来自自动生成的 `GITHUB_TOKEN`，无需额外 Maven Secret。只有发布 job 授予 `contents: read` 和 `packages: write`；已有 Maven 包需允许当前仓库 Actions 写入。仓库外消费 GitHub Packages 时仍需配置读取凭证及 Maven server id `forge-release`。

源码仓库已重命名为 `crane199709/saas-forge`，`MAVEN_RELEASE_URL` 使用 `https://maven.pkg.github.com/crane199709/saas-forge`。仓库外消费方若固定配置旧仓库地址，也应更新 Maven repository URL；Maven 坐标和 npm 包名保持不变。

上述 Variables 与 npm Secret 全部未配置时，CI 使用临时仓库；自动提供的 GitHub 身份和 token 不触发正式发布。任一项配置后进入 remote，缺项会失败。remote 仅接受上述两个目标，npm 可见性不设默认值；本地模式固定 `restricted`，继续使用临时 Verdaccio。三个发布包使用已选定的 `@crane199709` scope；正式发布前须确认该 scope 的写权限并确定包可见性。

目标、必填参数和 access 校验，以及 `npm whoami` 身份预检，都在 Maven deploy/npm publish 前执行。身份预检不代表已经确认目标 scope 的写权限、付费私有包资格或全部发布策略。临时 npmrc/settings.xml 只保存环境变量引用，不落盘密钥。

```sh
# 本地正式发布需先在环境中提供上表配置、GITHUB_REPOSITORY、
# MAVEN_RELEASE_USERNAME、MAVEN_RELEASE_PASSWORD；不要把凭证写进命令历史。
scripts/release/publish.sh remote v0.1.0
```

Maven 与 npm 发布不是跨仓库事务：其中一个失败时可能已经发布部分制品，需要检查目标仓库后处理，不能盲目覆盖同版本。正式发布由维护者安排；上传成功与仓库外消费验收是两个独立结果。

## 发布后的可用性与恢复验证

npm 上传成功后，安装元数据和 tarball 可能尚未可读。[npm 发布扫描说明](https://github.blog/changelog/2026-07-28-npm-publish-time-malware-scanning-and-dual-use-metadata/) 提示自动化流程需容忍此延迟。单版本查询返回 200 也不足以证明可安装：安装客户端读取的是包元数据（packument）。

`remote` 发布完成后先轮询三个包的安装元数据，确认 `versions[本次版本]` 存在且对应 tarball 可下载，再执行仓库外 Maven/npm 消费。三个包共享 20 分钟总预算（含 HTTP 请求），每轮间隔 15 秒。仅对短暂不可用的 HTTP 状态和网络错误重试；401/403、版本不匹配等立即失败。超时明确失败，不代表包没有上传成功。本地 Verdaccio 模式跳过等待。

若上传已成功、只有消费验证失败，不要重跑发布 job，也不要删除或覆盖同版本。使用 **Verify published SDK** 手动工作流，选择含此修复的分支，`tag` 输入已发布标签（如 `v0.1.0`）。该标签须与所选分支全部源码版本一致；工作流只授予 `packages: read`，复用现有 Variables、npm Secret 和 GitHub 自动 token。它只等待并消费既有制品，不运行 Maven deploy、npm publish、前端构建或镜像发布。

本地使用相同配置执行：

```sh
scripts/release/publish.sh consume v0.1.0
```

轻量 HTTP/CLI 回归（不连接正式仓库）：

```sh
python3 -m unittest discover -s scripts/release -p 'test_*.py'
```

## 0.1.0 正式发布记录

正式标签 `v0.1.0` 固定指向 `1e57298beed8f2aa53531afce29dfeb2f34ecf20`，不移动标签或覆盖同版本。[发布 run 41164401705](https://github.com/crane199709/saas-forge/actions/runs/41164401705) 的四个质量门通过，Maven 制品与三个公开 npm 包上传成功，六个正式 `0.1.0` 镜像发布及按 digest 回拉通过。原 run 的发布 job 随后因 npm 安装元数据短暂 404 而失败；该失败记录保留，上传成功不作为消费验收成功。

[只消费恢复 run 41165936334](https://github.com/crane199709/saas-forge/actions/runs/41165936334) 已成功，使用修复后的 `Verify published SDK` 等待并消费既有 `0.1.0`：隔离 Forge 缓存解析 Maven 依赖，安装三个 npm 包、编译类型示例、加载 Vite 配置并运行 lint CLI 全部通过。没有重新发布制品；不要再次运行上文的 `remote v0.1.0` 发布命令。另已在仓库外通过匿名公开 npm registry 执行：

```sh
pnpm --package=@crane199709/sub-app-kit@0.1.0 dlx forge-create-sub-app billing 5180
```

生成 `billing-sub-app` 成功，生成工程的三个 SDK 包依赖均为 `0.1.0`；这只证明公开下载与生成命令可用，不替代生成工程的构建、部署或试点验收。
