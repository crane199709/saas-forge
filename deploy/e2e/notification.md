# 通知中心真实链路验收

从仓库根目录执行：

```sh
FORGE_NOTIFICATION_E2E=1 deploy/e2e/run.sh
```

或在 `frontend` 执行 `pnpm test:e2e:notification`。需要本机 Docker、Java、pnpm、Python 3、curl 和已缓存或可下载的项目依赖、Playwright Chromium。通知模式与 `FORGE_TEMPLATE_SERVICE` 互斥；默认主链路和模板验收仍使用原配置。

该模式创建独立 Compose project，仅向回环地址暴露临时 PostgreSQL（15432）、Redis（16379）与 RocketMQ 5.3.2 Proxy（18081）。不挂载 `deploy/data`；结束时删除本轮容器和卷。Topic 和消费组复用仓库初始化脚本，初始化成功后才启动服务。IAM、平台和 Demo 均显式启用该 Broker。服务启动通过受控环境隔离，不接收外部数据库目标。

浏览器地址为租户端 `http://127.0.0.1:15174`、Demo remote `http://127.0.0.1:15175`、平台端 `http://127.0.0.1:15173`，网关为 `http://127.0.0.1:18000`。专用 seed 只增加订单关闭权限和独立公告 Operator/角色，不写入 Notification 或 Todo。

`frontend/e2e/main-flow/notification-flow.spec.ts` 不拦截网络、不注入会话：真实登录后创建 Demo 订单，验证 SDK → Outbox → RocketMQ → IAM 收件箱、已读、业务路由及页面关单，再验证 Todo 消失、租户切换隔离、两端公告发布/撤回和全部/指定租户投放。

截图和 Playwright 失败产物在 `/private/tmp/notification-center-qa/`。Java、Compose 和 Topic 初始化日志目录在结束时打印。默认 CI 主链路不自动启用该可选模式。
