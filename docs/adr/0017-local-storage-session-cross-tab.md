# 会话存于 localStorage，所有标签页共处同一租户，刷新跨标签页互斥

两个 Shell（tenant-app、platform-console）与 Sub-App 开发宿主把会话存进 `localStorage`，存储键仍为 `forge.tenant.session`、`forge.platform.session`。此前存于 `sessionStorage`，每个标签页都要单独登录，关闭标签页即丢失会话。复制标签页时浏览器还会复制 `sessionStorage`，两个标签页持有同一个一次性刷新凭证，先后刷新即被 IAM 判为重放并撤销整个授权族（见 [tenant-login.md](../tenant-login.md)）。改为共享存储后，同一浏览器的标签页共享登录，关闭浏览器后仍保持登录。详细规格见 [前端 Shell 规格第 7 节](../../.scratch/frontend-shell/spec.md#7-会话存储改为-localstorage)。

- **所有标签页共处同一租户**：会话只有一份，SDK 监听 `storage` 事件跟随其他标签页的改写。同一 Membership 下的刷新只通知订阅者，权限与菜单按新 Token 重新读取；Membership 变化（切换租户、登录）或会话清除时，SDK 提升会话代次，本标签页在旧会话下发起的请求以 `auth.sessionChanged` 失败，不会带着新 Token 重发；tenant-app 卸载全部 Sub-App、清空共享 Pinia 状态后回首页，首页按新会话显示租户首页或登录。不支持不同标签页停留在不同租户。
- **刷新与切换跨标签页互斥**：iam 的刷新凭证一次性使用（`reuseRefreshTokens(false)`）。角色变更等使所有标签页的下一次请求同时返回 401 时，各标签页会同时刷新，晚到的一方用已轮换的旧凭证刷新就会撤销授权族、登出全部标签页。SDK 在标签页内的串行队列之外，用 Web Locks（`navigator.locks.request('forge.<realm>.rotation', ...)`）让刷新与切换在各标签页之间串行；取得锁后重新读取存储，刷新凭证已被其他标签页轮换时直接采用新会话，不再调用刷新接口。目标浏览器都支持 Web Locks，不做降级实现。
- **失效判定不变**：只有刷新凭证被拒绝才清除会话，服务端或网络故障保留会话；重放撤销与即时失效（[ADR-0005](0005-immediate-revocation-via-membership-version.md)）不变。

## 风险

- 刷新凭证有效期 7 天（`refreshTokenTimeToLive`），关闭浏览器后在此期间仍保持登录。公用电脑上必须主动退出；不提供「记住我」选项。
- `localStorage` 与 `sessionStorage` 一样能被同源脚本读取，XSS 的风险等级不变；它保留得更久，凭证泄漏后的影响窗口也更长。
- 不迁移 `sessionStorage` 中的旧会话，上线后用户需重新登录一次。

## Considered Options

- 保留 `sessionStorage`：多标签页各自登录体验差，复制标签页仍会因共享刷新凭证触发重放撤销。
- 允许各标签页停留在不同租户（按标签页保存 Membership、共享刷新凭证）：一个刷新凭证只绑定一个 Membership，切换即轮换，各标签页无法各自持有不同租户的有效凭证；要支持就得让 IAM 按标签页签发独立授权，复杂度与收益不相称。
- 为重放检测加宽限期，容忍并发刷新：削弱「旧凭证一经识别为重放即撤销」的语义，被窃取的凭证也能在宽限期内使用；在客户端串行刷新即可避免并发。
- 用 `BroadcastChannel` 选主标签页统一刷新：需要处理主标签页关闭、选主切换，Web Locks 已直接提供跨标签页互斥。
