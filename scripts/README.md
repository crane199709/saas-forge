# 脚本

脚本只自动化已有的明确流程。日常应用启停使用[原生开发流程](../docs/native-local-development.md)，局部检查使用[分层验证](../docs/local-verification.md)；下列托管、replace/restore 和完整矩阵保留为集成验收工具。

- `local-https-development.sh <start|status|stop> edge`：独立 HTTPS 入口生命周期，不启动或停止原生 Console/后端；`setup|hosts|trust-ca|doctor` 用于独立准备与诊断。

- `validate-nacos-config.sh`：校验四个环境的 Nacos 非敏感配置清单，或只校验传入的单个环境。
- `validate-local-compose-jwt.sh`：校验本地 Compose 是否向 IAM 注入 JWT 配置、只读挂载私钥，并在 `.env.example` 声明初始化变量。
- `initialize-local-iam-signing-key.sh`：显式生成 Git 忽略的本地 PKCS#8 RSA 私钥，并在迁移后的 IAM 数据库中初始化与其匹配的唯一 ACTIVE Signing Key 元数据。
- `local-service-replacement.sh <doctor|replace|status|restore> <gateway|iam-service|tenant-access-service|entitlement-service|audit-service>`：统一入口复用的单目标生命周期命令。在不构建应用镜像、不删除卷的前提下，受控切换一个唯一健康的容器服务和对应本机 JVM；只读取受限 Secret，输出不包含其值，`replace` 失败时自动恢复选定容器。
- `publish-nacos-config.sh <environment>`：使用独立配置发布身份将一个已校验的环境清单发布到同名 Nacos namespace。
- `verify-nacos-acl.sh`：针对临时或本地 Nacos 验证每个工作负载只能读取自己的配置，且不能发布配置。

- `acceptance-handoff.mjs`：准备/校验独立环境交接并关联同轮后端与浏览器证据，详见[交接说明](../docs/acceptance/independent-verification.md)。

前端在独立仓库执行 `pnpm run dev` / `pnpm run verify`。已移除的旧浏览器和双 Console 入口只从 Git 历史追溯；未迁检查见[#207 清单](../docs/acceptance/issue-207-cleanup.md)。
