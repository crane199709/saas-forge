# Sub-App 从 0.1.0 升级到 0.2.0

本文覆盖使用已发布 `0.1.0` SDK、UI、sub-app-kit 的既有业务前端。`0.1.0` 与 `0.2.0` 都不是产品正式版；验收时的 `0.2.0-SNAPSHOT` 是固定候选，不能据此宣称目标发布仓库已经可消费。两个试点若已经使用 SNAPSHOT，应按其实际提交审阅差异，不能伪称来自已发布 0.1.0。

## 合并升级差异

1. 固定工程提交、`package.json`、锁文件、业务页面/路由、自定义 Vite 配置以及当前部署入口。平台依赖版本、业务自身 `version`、后端 Resource Manifest version 分别维护：更新 Forge 依赖不要求改业务版本或 Manifest。
2. 使用与目标 Shell 配套的 kit，在业务目录外生成临时参考工程：

   ```bash
   pnpm --package=@crane199709/sub-app-kit@<配套版本> dlx forge-create-sub-app billing 5180 /tmp/billing-reference
   ```

   对照参考 `package.json` 合并三包与共享依赖。SDK、UI、kit 使用同一平台版本；Vue、Router、Pinia、Element Plus、i18n 与构建工具取配套版本表中的精确版本，不复制 monorepo 的 `workspace:` 协议，不自行放宽为范围。更新并提交锁文件，审阅安装结果。
3. 合并 `format`、`format:check`、`lint:style`、`start:<编码>`、视觉检查命令和 `build` 的样式检查；补齐对应精确工具依赖、`.prettierrc.json`、`.prettierignore`、`stylelint.config.mjs`、视觉配置。既有测试与项目自定义命令继续保留，视觉样例按现有页面调整，不直接把参考页面预期当作业务预期。
4. 复制配套规范 `docs/standards/sub-app.md`，将参考 `AGENTS.md` 的适用说明合入已有约定。审阅 `tsconfig.json`、`src/dev.ts` 与启动脚本，只有实际存在的差异才合入。旧版 `src/dev.ts` 的 `startDevHost({ appCode, routes, home })` 契约仍适用。
5. 在原 `vite.config.ts` 删除 `apiPrefixes`，保留应用编码、开发端口、`gateway`、`shellOrigins` 和业务构建覆盖项。删除手工添加的逐服务代理（例如 `/billing`）；开发 Shell、生产 Shell 与独立宿主固定代理 `/api` 和 `/iam`。生产 Shell 移除 `FORGE_API_PREFIXES`。
6. 保留业务 `src`、相对路由、路由名称、文案、功能及自定义配置。新版参考的默认 `home` 页面用于新建工程，不能覆盖已有业务页面，也不能机械改写后端 Manifest 的现有路由语义。格式工具可能改动排版，审阅时分别核对格式差异与行为差异。真实 0.1.0 模板订单默认按创建时间升序，新版参考默认降序；保留原页面时同时保留其排序语义，不把新建模板测试的顺序预期直接套到旧业务页。

## 接口、主题与环境切换

业务仍调用 `client.api('/billing/...')` / `client.page('/billing/...')`。SDK 使浏览器请求 `/api/billing/...`；宿主剥离 `/api` 后网关收到 `/billing/...`。不要在业务路径手工加 `/api`，也不要改网关的服务前缀。登录、OAuth2 回调与 Refresh 保持 `/iam` 及 Cookie 语义。

独立开发宿主使用隔离或自定义网关时，业务 `vite.config.ts` 须显式把地址传给 kit 的 `gateway` 选项，例如 `gateway: process.env.FORGE_DEV_GATEWAY`；kit 不会自动读取这个环境变量。只设置变量会仍访问默认 `localhost:8080`。未设置时可沿用 kit 默认地址，保留业务既有配置。

独立开发宿主的真实登录要求 IAM tenant 回调地址与宿主同源。可在隔离环境先运行 Shell，然后关闭它，在同一回调源启动业务 `pnpm dev`；不能绕过 SDK 的源校验。开发宿主与 Shell 加载都必须通过，开发模式自身依赖副本不能证明联邦共享单例正常。

主题由配套 Shell 或 kit 开发宿主提供；业务页只读 Theme Token。升级后检查主色、按钮、正文、背景与表格，逐张查看 1440 与 1024 截图。写死的颜色按实际差异改为既有 token，不复制新页面或覆盖业务样式来假装完成主题升级。

同一环境的 Shell、SDK、UI、kit 与受影响 Sub-App 配套切换；尚有旧 Sub-App 未升级时先不要单独切换新版 Shell。本次不承诺跨平台版本混跑。构建后部署整个 `dist`，由 Operator 按目标环境登记新的 `remoteEntry.js`、业务版本及最低共享依赖，核对 CORS、Manifest 菜单、Plan 与 Role；用户刷新后消费新入口。入口登记不会自动授予权限。

## 验收命令与成功判据

在业务工程内执行：

```bash
pnpm install
pnpm format:check
pnpm lint
pnpm lint:style
pnpm typecheck
pnpm build
pnpm dev
```

检查独立宿主的真实登录、保留页面和路由、业务请求，再让配套 Shell 加载同一 `dist/remoteEntry.js`。应能观察到业务请求 `/api/<服务前缀>/...` 返回成功、认证经过 `/iam`、页面随宿主主题呈现、无联邦共享依赖错误。还应回归项目自身测试，以及租户切换和权限边界。格式/样式检查失败时逐项处理已有页面，不通过取消检查绕过。加载失败时保留控制台错误、网络响应、入口地址、已安装版本和构建提交；菜单不可见时核对 `/iam/auth/access`、Manifest、Plan、Role 与登记环境。

平台提供固定旧版样例的可重复入口，供本地和 CI 使用；它只操作仓库外样例，不会改写任意业务工程：

```bash
# 先按候选说明完成新建模板消费，或显式提供其服务 JAR。
# 省略旧包目录时匿名从 npm 官方 registry 下载 0.1.0 三包并核对 integrity。
bash scripts/release/candidate.sh use /tmp/candidate \
  bash deploy/upgrade-smoke/sub-app.sh /tmp/sub-app-upgrade /tmp/published-0.1.0
```

候选目录须已通过 `candidate.sh build` 生成；若只保留了制品，先提供配套新建服务的 `FORGE_TEMPLATE_SERVICE`。记录 `provenance.json`（真实旧包来源、integrity、摘要与候选清单）、`baseline/`、`baseline-source-sha256.json`、`reference/`、`app/`、`upgrade-audit.json` 与浏览器证据。旧基线构建通过后，`format:check` 缺失红灯保存在 `red-missing-format.log`，升级后相同命令通过。代码保留校验允许统一格式化，但拒绝任何其他业务源码变化。

该样例的旧业务版本为 `1.7.3`，包含订单页、自有采购复核页、相对路由、自定义命令、sourcemap 与构建 define。升级后三包使用实际候选，两个宿主均访问真实隔离 IAM/网关/订单服务，不使用 HTTP mock。新复制的视觉样例只 mock HTTP、预置假会话，用于长标题和空列表布局走查，不能替代真实认证与业务验收。本入口前端依赖升级结果不代表默认服务的数据升级、IO/storage 或两个试点通过。

候选源码或包摘要变化后，重新执行受影响场景，不能继续引用旧验收结果。目标仓库的只读消费复验在制品发布后执行，不能把临时 registry 成功当作目标仓库成功。

## 已有本地快照包的试点差异

ai-platform 的实际基线已经声明 `0.2.0-SNAPSHOT`，但根 workspace 通过 `file:.forge-packages/*.tgz` 使用本地包。版本字符串相同不能证明内容相同：本次 SDK 原包摘要与固定候选一致，UI/kit 两包不同，需要一起核对候选清单与配套共享版本，更新必要原包和规范副本，保留业务版本、页面与相对路由。

替换同名 `file:` tarball 后，普通 `pnpm install --lockfile-only` / `pnpm install --frozen-lockfile` 可能沿用旧完整性记录与旧内容地址缓存。显式刷新相关包的锁记录，例如 `pnpm update @crane199709/ui @crane199709/sub-app-kit --filter <业务前端包名> --lockfile-only`，审阅 lockfile，再 frozen install。验收时核对实际安装文件与原始候选 tarball 一致，并观察宿主主题，不能只查看源码目录中的新 tarball。具体输入与结果见[AI 试点验收](../.scratch/v020-upgrade/acceptance-07.md)。
