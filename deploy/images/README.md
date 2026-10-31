# 镜像

前提：Java 17、Maven、Node 24、pnpm 12.4.2、Docker；在 frontend 执行 `pnpm install --frozen-lockfile`。
根目录运行 `scripts/images/build.sh` 串行构建四个后端服务和两个 Shell，标签取统一版本。IMAGE_PREFIX 默认 forge，可指定本地命名空间。本地脚本只构建，不推送；`IMAGE_TAG` 可覆盖标签，`IMAGE_SOURCE` 可提供源码仓库 URL。

后端使用 Spring Boot build-image（首次下载 builder/run image）。images profile 排除本地 application*.yml；必须 clean，避免旧配置残留。运行时挂载部署专用 `/config/application.yml` 并设置 `SPRING_CONFIG_LOCATION=file:/config/application.yml`，不要直接使用仓库本地默认配置。

## GHCR 发布与验证

仓库 Actions variable `IMAGE_PREFIX` 设置为 `ghcr.io/crane199709/saas-forge-v2`。工作流只接受 `ghcr.io/<当前仓库 owner>/<单段命名空间>`，不接受其他 registry 或 owner。未配置时仍构建六个镜像，不登录、不推送。

发布标签 `v<统一版本>` 在完整质量门通过后调用共享的 `images.yml`，推送版本标签；Actions 中手动运行 **Build and publish images** 可单独验证六个镜像，不发布 Maven/npm，也不创建版本标签。手动运行始终使用 `sha-<完整提交 SHA>`，包括 main，避免覆盖正式版本。首次使用手动入口须先将工作流合入默认分支，然后可选择待验证分支。

镜像名称为 `forge-gateway`、`forge-iam`、`forge-platform`、`demo-service`、`tenant-app`、`platform-console`，例如 `ghcr.io/crane199709/saas-forge-v2/forge-iam:0.1.0`。六个镜像均带 `org.opencontainers.image.source` 指向当前仓库。

CI 使用自动生成的 `GITHUB_TOKEN` 登录；仅镜像 job 授予 `packages: write`，不配置额外 PAT。GHCR 新包默认私有，工作流在推送前检查已有包、推送后检查每个包保持 private 且关联当前 repository；已有同名包需提前关联当前仓库并允许该仓库 Actions 访问。工作流不会修改包可见性或权限。推送后记录 digest、删除本地标签，再按 digest 拉取并比对镜像 ID；摘要保留六个不可变拉取引用。此验证不代替服务健康与试点主链路验收。

在开发机或部署环境拉取私有包，使用有目标包读取权限、至少 `read:packages` scope 的 **personal access token (classic)**（组织启用 SSO 时还需相应授权）：

```sh
printf '%s' "$GHCR_READ_TOKEN" | docker login ghcr.io -u "$GITHUB_USERNAME" --password-stdin
docker pull ghcr.io/crane199709/saas-forge-v2/forge-iam:0.1.0
```

生产部署优先使用工作流摘要里的 `镜像名@sha256:...`。参见 [GitHub Container registry 官方说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)。

## 后端配置

四个服务均设置 spring.application.name、server.port、spring.data.redis.host/port；需要 Nacos 时设置 spring.config.import、spring.cloud.nacos.server-addr 及认证信息，或直接提供完整挂载配置。

| 服务 | 必须提供的属性 |
| --- | --- |
| gateway | forge.security.issuer/jwk-set-uri、forge.security.platform.issuer/jwk-set-uri、forge.gateway.services 和 Spring Cloud Gateway 路由 |
| iam | forge.security.issuer、forge.security.platform.issuer、forge.iam.signing-key-file、forge.iam.platform.signing-key-file、forge.iam.invitation.accept-url、forge.iam.sub-app.environment、forge.iam.manifest.tokens |
| platform | forge.security.realm=platform、forge.security.platform.issuer/jwk-set-uri、forge.manifest.iam-uri/token/version、forge.platform.iam.uri/token、forge.platform.provisioning.services、forge.tenant.ignore-tables |
| demo-service | forge.security.issuer/jwk-set-uri、forge.manifest.iam-uri/token/version、forge.storage.endpoint/bucket/access-key/secret-key |

iam/platform/demo 另需 spring.datasource.url/username/password、spring.flyway.user/password/schemas/default-schema/create-schemas=false、forge.mq.endpoints、mybatis-plus.mapper-locations（classpath*:/*/mapper/**/*.xml）。iam 需要 mybatis-plus.configuration.arg-name-based-constructor-auto-mapping=true。标准 Spring 环境变量规则：点替换下划线、删除连字符、转大写，例如 FORGE_SECURITY_JWKSETURI、SPRING_DATASOURCE_PASSWORD。列表与 token map 建议使用挂载 YAML。非环境不变量如 manifest version、ignore-tables、mapper 扫描路径参照各服务 application.yml 保留；环境地址和密码全部替换。数据库账号和 schema 按接入指南预建；IAM 密钥使用持久化可写卷。issuer 浏览器可达，JWKS/Manifest 使用容器间地址。

## Shell

监听 8080，必填 FORGE_GATEWAY_URL（内部 http(s) origin，无路径或尾斜杠）和 FORGE_API_PREFIXES（空格分隔路径段）。tenant 使用 `iam demo`，platform-console 使用 `iam platform`，新服务加入对应前缀。SDK 保持同源，启动时生成 nginx API 代理配置；修改变量重新创建容器即生效。

```sh
docker run --rm -p 5174:8080 -e FORGE_GATEWAY_URL=http://gateway:8080 -e 'FORGE_API_PREFIXES=iam demo' forge/tenant-app:0.1.0
```

API 前缀不能与 Shell 页面路由重名。嵌套路由回退到 index.html；入口、fallback、runtime-config.json 为 no-store；assets 下 Vite hash 资源长期缓存。runtime-config.json 只公布同源模式，不公布内部地址。边缘 TLS 与 forwarded header 策略由部署环境配置。
