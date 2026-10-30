# SDK 版本与发布

在仓库根目录执行：

```sh
python3 scripts/release/version.py set 0.1.0
python3 scripts/release/version.py check v0.1.0
```

命令同步 backend reactor 中的 Forge 坐标和 frontend 根目录、packages、apps 的版本；不改第三方版本。发布标签必须为 `v<语义版本>`，拒绝 SNAPSHOT、标签与文件不一致。修改版本后更新根目录 CHANGELOG.md，单列不兼容变更，再提交与推送标签。

标签工作流复用后端 verify、前端完整检查与 mock E2E、真实主链路 E2E；所有质量门通过后才能发布。模板冒烟由后续工单 08 补充，镜像由工单 07 补充。

Maven 发布包含 forge-dependencies、forge-parent、forge-sdk 聚合 POM、全部 forge-sdk-* 与 forge-architecture；父 POM 是仓库外解析继承链所必需的。平台服务和 demo-service 设置 deploy/source skip。目标 URL 通过 `-Dforge.release.url` 注入，凭证对应 Maven server id `forge-release`。

npm 先构建再使用 `pnpm pack`，由 pnpm 将 `workspace:` 替换为实际版本并应用 `publishConfig`，最后发布同一 tarball。`sub-app-kit` 的 Vite 配置携带当前 Shell 的共享依赖版本快照，CLI 发布为 JavaScript。生成器在任意仓库生成完整工程的能力仍由工单 06 实现；本工单只验证包安装、导出、类型声明、构建配置与 lint CLI。

## 临时发布验证

需要 JDK 17、Python 3、Node 24、pnpm 12.4.2 与 Docker，先执行 `pnpm --dir frontend install --frozen-lockfile`。本地复现：

```sh
scripts/release/publish.sh local v0.1.0
```

只在新建的临时目录发布 Maven file 仓库，并在绑定 127.0.0.1 的临时 Verdaccio 容器中发布 npm 包。容器退出时清理，目录保留供排查。空 Maven 工程只导入 BOM 并声明 SDK/架构模块，不设内部依赖版本，使用独立 Maven 缓存解析（仅复用本机第三方缓存，排除全部 `io.saasforge`）；空 npm 工程安装三个包、编译类型使用示例、加载 Vite 配置并执行编译后的 lint CLI。验证服务制品不存在且 Java 模块附源码包。首次运行需要下载 Docker 镜像与 Maven/npm 依赖。

## 正式仓库配置

GitHub Variables：`MAVEN_RELEASE_URL`、`NPM_RELEASE_REGISTRY`；可选 `NPM_RELEASE_TAG`（默认 latest，预发布建议设 next）。GitHub Secrets：`MAVEN_RELEASE_USERNAME`、`MAVEN_RELEASE_PASSWORD`、`NPM_RELEASE_TOKEN`。

全部未配置时 CI 自动执行临时发布；只配置部分会失败，避免误判发布成功。全部配置后执行 remote 模式并从目标仓库消费验证。npm registry URL 不嵌入凭证；临时 npmrc/settings.xml 只保存环境变量引用。

```sh
# 先在环境中提供上述五项参数，再执行；不要把凭证写进命令历史。
scripts/release/publish.sh remote v0.1.0
```

Maven 与 npm 发布不是跨仓库事务：其中一个失败时可能已经发布部分制品，需要检查目标仓库后处理，不能盲目覆盖同版本。仓库选型与正式发布由维护者安排，本次实现不推送版本标签。
