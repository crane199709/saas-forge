# 07: 服务与 Shell 镜像

**What to build:** gateway、iam、platform、demo-service 用 Spring Boot 插件的镜像构建能力生成镜像，不手写 Dockerfile。两个 Shell App 生成静态文件服务镜像，提供 history fallback，运行时配置在容器启动时注入，不编译进产物。发布工作流构建这些镜像，镜像仓库确定后再推送。只负责产出镜像，不涉及编排。见[规格 · 镜像](../spec.md#镜像)。

**Blocked by:** 04 统一版本与发布流程

**Status:** ready-for-human

- [x] 四个后端服务可用一条命令构建镜像，镜像标签为统一版本号；镜像内不含任何密钥或环境地址，所需配置通过环境变量或 Nacos 提供
- [x] tenant-app 与 platform-console 的镜像：静态文件服务、history fallback（嵌套路由可直接刷新）、运行时配置由容器启动时的环境变量生成；入口与运行时配置禁用缓存，带 hash 的资源长期缓存
- [ ] 发布工作流构建全部镜像；镜像仓库地址与凭证由 CI 密钥提供，未配置时只构建不推送
- [x] 文档说明每个镜像需要的环境变量（对应各服务的必须配置项）
- [ ] 验证：用构建出的镜像启动后端四个服务与依赖，健康检查通过；Shell 镜像直接打开嵌套路由能正常显示，修改运行时配置后重启容器即生效。主链路 E2E 改用镜像的成本若过高，在 Comments 中注明，留到试点环境验证

## Comments

- 2026-10-30：新增 scripts/images/build.sh，Spring Boot build-image 构建后端；images profile 排除本地 application*.yml。四服务 clean install（跳过测试）通过，检查打包 jar 均不含本地 application 配置。生成服务补齐同样的 Boot 镜像入口。
- 两个 Shell 前端构建与 Docker 镜像构建通过；临时 nginx 容器验证 tenant 的 /apps/orders/nested、platform 的 /operators/nested 返回 200 与 no-store；runtime-config.json 为 no-store，hash JS 为 max-age=31536000, immutable。改变网关变量后重新创建容器，nginx -T 确认新 upstream 生效；临时容器已删除。尚未用浏览器连接完整平台主链路。
- 发布工作流已接入只构建任务。条件推送配置被自动审批拒绝：尚未确认可信 registry，持久化向任意 secrets 指定地址推送的逻辑未获准。按安全替代方案保留只构建；需确认可信目标后补齐，因此对应子项未勾选。未推送任何镜像。
- 主链路 E2E 当前以本地 Java/Vite 进程启动；切换镜像需要部署配置与依赖网络调整，留待试点环境验收，不将 HTTP 静态文件检查当作主链路验证。

- 后续实测：四个后端镜像均通过 Spring Boot build-image 构建（统一 0.1.0 标签）。首次 JRE 下载导致 creator 51；宿主与 builder 容器内官方 URL 均可达，重试成功，未关闭 TLS 或修改系统代理。
- 四个镜像在全新临时 Docker 网络、PostgreSQL 17 与 Redis 8.8.1 中串行启动，/actuator/health 全部 status=UP；DB/Redis 健康。采用现有 E2E 的 MQ/Nacos 禁用模式，未验证真实 MQ/Nacos。临时容器与网络均清理，证据目录 /tmp/forge-images-InNTHt。综合验证子项因真实依赖与浏览器主链路未覆盖仍保留未勾选。
- 已合并 08 的模板冒烟实现，发布 images 任务依赖 template-smoke 通过。实现转 ready-for-human 供审阅；剩余为可信仓库推送配置与试点完整链路验收。
