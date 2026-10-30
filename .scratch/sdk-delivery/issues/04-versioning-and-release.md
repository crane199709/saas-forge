# 04: 统一版本与发布流程

**What to build:** 后端可发布模块与前端 npm 包使用同一个版本号；推送版本标签时，CI 先校验版本，再跑质量门，然后发布 Maven 制品（BOM、forge-sdk-*、forge-architecture，带源码包）与 npm 包（`@forge/sdk`、`@forge/ui`、`@forge/sub-app-kit`）。仓库地址与凭证全部来自参数和 CI 密钥；私有仓库选定之前，CI 发布到任务内临时启动的本地 Maven 仓库与本地 npm registry 来验证流程。见[规格 · 统一版本与发布](../spec.md#统一版本与发布)。

**Blocked by:** 01 CI 质量门

**Status:** ready-for-agent

- [ ] 提供一条命令同步设置后端与前端全部版本号；发布前校验标签与各处版本一致且不是 SNAPSHOT，不一致时失败
- [ ] Maven：可发布模块配置发布与源码包；平台服务（gateway、iam、platform）与 demo-service 跳过发布；发布目标地址与凭证由参数提供，构建配置中不写死地址
- [ ] npm：`@forge/sub-app-kit` 取消 private，补齐 `files`、`publishConfig` 与构建脚本；各包的 `workspace:` 依赖在发布产物中替换为实际版本
- [ ] 新增发布工作流：标签触发 → 版本校验 → 质量门 → 发布；未配置正式仓库密钥时发布到任务内临时本地仓库，并从中解析一次 BOM 与 npm 包，作为自检
- [ ] 新增仓库根目录的变更日志，记录每个版本的变更，单列不兼容变更；首个版本 0.1.0
- [ ] 验证：在临时本地仓库中，一个只引用 BOM 与 starter 坐标的空 Maven 工程能解析全部依赖；一个空 npm 工程能安装三个包且类型声明可用；平台服务 jar 不出现在本地仓库中
