# 浏览器业务请求统一走 /api，由 Shell 剥掉前缀

浏览器侧访问 Business Service 的路径统一为 `/api/<服务前缀>/...`：SDK 的 `api()` / `page()` 在传入路径前加 `/api`，子应用源码继续写 `/<服务前缀>/...` 不变。`/api` 由 Shell 剥离后转发网关——开发环境是 Vite `rewrite`，生产是 Nginx `rewrite`——所以网关路由与 `forge.gateway.services` 的契约完全不变，仍是 `/orders/**` + `StripPrefix=1`。Shell 因此只需要两条固定代理：`/api`（业务）与 `/iam`（登录、OAuth2、Refresh 等认证端点，它们不经 `api()`）。

选择在 Shell 剥离而不是网关，是因为网关侧要动就得同时改路由谓词、`StripPrefix` 与安全链的匹配器，而这些目前由 Nacos YAML 与启动期构建的 `SecurityWebFilterChain` 分别持有；壳层剥一个路径段只需要一行 `rewrite`，且"浏览器入口"与"网关入口"从此是两个概念：只有浏览器请求带 `/api`，服务间与运维调用仍直连网关或服务。

认证端点不经过 `api()`，这是有意保留的：OAuth 回调、登录会话 Cookie 与同源校验都依赖 `/iam/...` 的既有路径，套一层 `/api` 会同时改动回调地址、Cookie 路径与 CORS 判断，收益为零。

## Considered Options

- 网关全局过滤器或每条 route 加 `/api` 谓词 + `StripPrefix=2`：需要改 Nacos 里每条路由，并把"浏览器入口"知识塞进网关；网关本就按服务前缀路由，多一层 `/api` 只是转发负担。
- 每个服务前缀各配一条代理（现状）：新增服务必须改 Shell 与子应用宿主，正是本次要消除的摩擦。
- 让子应用自己把 `/lowcode` 改成 `/api/lowcode`：把约定散落到每个子应用源码，且以后改约定要全量改业务代码。
- 把 `/api` 做成可选（SDK 开关）：会立刻退化成"有的子应用带、有的不带"，两套代理又回到逐前缀配置。

## Consequences

- `FORGE_API_PREFIXES` 与 `sub-app-kit` 的 `apiPrefixes` 被删除，属于破坏性变更，需同步 `CHANGELOG`、`docs/sub-app.md`、`deploy/images/README.md` 与两套脚手架模板。
- 直接打网关的调用方不认 `/api`；诊断与运维脚本继续按网关路径访问，文档需区分这两个入口。
- Shell 的代理配置变成常量两条，新增服务不再触碰前端配置，也不再需要为未知路径做通配代理。
