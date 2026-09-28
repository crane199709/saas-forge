# 统一 Console 原生开发

正式前端位于独立 [saas-forge-web](https://github.com/crane199709/saas-forge-web)，具体工具链和配置见该仓库的 [independent-development.md](https://github.com/crane199709/saas-forge-web/blob/main/docs/independent-development.md)。取得前端源码后，在前端根目录执行锁定安装、检查和启动：

```sh
pnpm install --frozen-lockfile
pnpm run verify
pnpm run dev
```

按前端说明在 Git 忽略的个人配置中设置 API Origin 与受信 HTTPS Console Origin；缺少配置时应失败，不使用演示后端替代正式业务。前端只需要 Node/pnpm 和已发布的固定版本 Client，不需要后端源码、Maven/JDK 或相邻目录。

后端由开发者 IDE Run/Debug，HTTPS、域名和证书单独准备，见[原生开发总入口](native-local-development.md)。`local-https-development.sh <start|status|stop> edge` 只管理 Edge。旧 `frontend ... platform|tenant|all` 和双应用启动命令已移除；不要用它们判断原生前端状态。

真实页面仍通过受信 HTTPS/Gateway，遵守 Cookie、CORS、CSRF 和单账号会话边界。进程启动与构建通过只证明开发入口可用；业务验证连接已启动环境，使用[独立交接](acceptance/independent-verification.md)，不接管用户后端。

旧双 Console 开发说明与源码可按[历史追溯](acceptance/console-history-reproduction.md)恢复，历史记录不作为当前操作指令。
