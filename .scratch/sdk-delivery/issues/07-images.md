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

- 2026-10-31：已确认 GHCR 私有镜像目标 `ghcr.io/crane199709/saas-forge-v2`；实现共享 images 工作流，release 质量门后调用，独立 workflow_dispatch 使用 SHA 标签。IMAGE_PREFIX 未设仍只构建；推送用 job 级 GITHUB_TOKEN，限制当前 owner 的 GHCR，检查 private/repository 关联，删除本地标签后按 digest 重拉并比对 ID。六镜像补 source label。仅完成本地轻量检查，真实推拉由远端 CI 验证后记录，不提前勾选。
- 本次轻量验证：两个工作流 YAML 解析、所有新增 run 段与 build.sh 的 bash -n、POM XML 解析、git diff --check、四服务 images profile 离线 Maven validate 均通过。effective-pom 因本机缺失 Maven Help 插件依赖无法离线执行；未重建镜像、未向远端推送。
- 首轮远端 CI（images run 41158737468、backend run 41158723728）发现父 POM 不符合 Spotless 的空元素与节点排序规则，尚未进入镜像推拉。随后仅对父 POM 执行 `mise exec java@oracle-17.0.12 -- mvn -B -N -f backend/pom.xml spotless:apply`，同参数 `spotless:check` 与 `git diff --check` 均通过；待集成后重新运行远端 CI。
- 第二轮远端 images run 41158999818：六镜像构建与 GHCR 登录成功，gateway 推送成功（digest `sha256:f7973ad732ab70661ebe033807a5be6f8a3e9f36c0e000fe48412c79329e7585`），随后校验退出但旧日志未标明具体断言，根因仍待核实。补充 source/包元数据/digest/镜像 ID 的期望值与实际值诊断；共享只读包检查脚本用于构建前预检、推送前与推送后校验，不改变权限或可见性。
- 诊断改动本地验证：工作流 YAML 解析、全部 run 段及 check-package.sh 的 bash -n、git diff --check 通过；未本地重建或推送镜像，未将第二轮失败推断为权限或 digest 根因。
- 第三轮远端 images run 41159533036 的前置检查明确返回 `visibility=private repository=null`。Container 包 API 可不返回 repository，原强制比较误判失败；现严格核验 private、包名、owner，repository 非空才要求匹配，null 记录验证边界。source 标签标记源码，令牌实际推拉验收访问权，不声称已验证 UI 关联。真实响应形状回归先复现 private+null 失败，再修正通过；保留 public、其他包名/owner/repository 的拒绝校验，不修改 GHCR 权限或可见性。
- 本地 `python3 scripts/images/test-check-package.py` 7/7 通过（临时 curl 响应夹具调用真实脚本，覆盖 private+null、public、其他 repository、其他包名、其他 owner、匹配 repository、owner 大小写）；bash -n 与 git diff --check 通过。未本地重建镜像，完整推拉仍待远端重跑。
