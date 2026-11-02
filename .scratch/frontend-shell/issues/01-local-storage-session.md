# 01: 会话改存 localStorage，跨标签页同步与刷新互斥

**What to build:** 两个 Realm 的会话改存 `localStorage`，同一浏览器的多个标签页共享登录，关闭浏览器后仍保持登录。一个标签页刷新 Token、切换租户、退出或登录时，其他标签页随之更新。多个标签页同时刷新时只调用一次刷新接口，不会因刷新凭证重放而撤销整个授权族。只改 SDK 与存储参数，不动界面。见[规格第 7 节](../spec.md#7-会话存储改为-localstorage)。

**Blocked by:** saas-forge-mvp 工单 22–26

**Status:** resolved

- [x] tenant-app、platform-console 与 `@forge/sub-app-kit` 的 `dev-host.ts` 创建客户端时传入 `localStorage`；存储键不变，不迁移 `sessionStorage` 中的旧会话
- [x] SDK 监听 `storage` 事件：其他标签页刷新 Token 时通知 `onSessionChange` 订阅者；切换租户（`membershipId` 变化）时本标签页卸载 Sub-App、清空共享 Pinia 状态并回首页；退出或会话清除时回到登录；登录时停留在登录的标签页进入首页
- [x] 会话被其他标签页改写时 `generation` 递增，本标签页在旧会话下发起的请求不带新 Token 重发
- [x] `refresh` 与 `rebind` 在现有标签页内队列之外，用 `navigator.locks.request('forge.<realm>.rotation', ...)` 跨标签页互斥；取得锁后重新读取存储，刷新凭证已被其他标签页轮换时直接使用新会话，不调用接口
- [x] 「刷新凭证被拒绝才清除会话，服务端或网络故障保留会话」的现有行为不变
- [x] 新增 ADR-0017（编号以落地时为准）：会话存于 `localStorage`、所有标签页共处同一租户、跨标签页刷新互斥的原因，以及 7 天刷新凭证带来的风险
- [x] 同步 [docs/tenant-login.md](../../../docs/tenant-login.md) 与 [docs/platform-login.md](../../../docs/platform-login.md) 中关于会话存储的描述
- [x] Vitest（jsdom 无 `navigator.locks`，提供桩实现）：模拟其他标签页写入、切换、清除会话，本标签页行为符合规格 7.2 节；两个客户端实例共享同一存储并同时刷新，只调用一次刷新接口，两者都拿到新会话；在途请求遇到跨标签页切换时以 `auth.sessionChanged` 失败
- [x] Playwright（同一浏览器上下文开两个页面）：A 登录后 B 刷新即已登录；关闭全部页面再打开仍已登录；A 切换租户，B 的菜单与 Sub-App 数据随之切换；A 退出，B 回到登录；管理员修改该成员角色后 A、B 同时发起请求，两者都按新权限继续工作且不被登出
- [x] `pnpm typecheck`、`pnpm test`、`pnpm test:e2e`、`pnpm format:check` 通过
