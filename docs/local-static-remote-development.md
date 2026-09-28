# 统一 Console 的静态 Remote 验证

Remote 夹具、构建和真实宿主消费在独立前端维护，参见 [#10 验收](https://github.com/crane199709/saas-forge-web/blob/main/docs/acceptance/issue-10-remotes.md)。在前端运行 `pnpm run build:static-remote`，冻结制品保持历史 SHA-256；不得修改已有版本字节或校验和掩盖差异。

后端保留 `deploy/compose/local-https-development/remote-static.mjs` 及 `scripts/test/remote-static-delivery.test.mjs`。环境准备方通过 `SF_REMOTE_STATIC_DIRECTORY` 提供外部只读制品；不读取旧 `consoles/dist`，没有制品时返回 404。部署挂载由准备方显式配置，不能自动创建占位目录冒充已交付。

统一宿主继承语言、主题和权威 Tenant 品牌；Remote 不创建认证 Runtime、不读取 Token，只消费允许的宿主能力。模块、样式、图片匿名加载，只对指定 Console Origin 返回精确 CORS，不返回凭据许可；API、Remote 和攻击来源的拒绝必须分别验证，不能合并旧白名单。

后端静态交付检查：

```sh
node --test scripts/test/remote-static-delivery.test.mjs scripts/test/local-https-development.test.mjs
```

前端 `scripts/remote-browser-checks.mjs` 连接已启动环境验证真实模块执行、样式、图片和清理。下载 200、构建或模拟结果不等于真实宿主消费；#10 已有证据不代替 #183–#189 的 Fresh 同轮完整安全验收。交接与待迁责任见[独立验证](acceptance/independent-verification.md)。

旧第四域/双 Console 的命令与截图仅作[历史追溯](acceptance/console-history-reproduction.md)，不再提供旧页面托管入口。
