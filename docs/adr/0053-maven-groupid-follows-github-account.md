# 公开 Maven groupId 跟随维护者的 GitHub 账号名

公开 Maven 制品的 groupId 由维护者可验证的 GitHub 命名空间决定，即 `io.github.<当前账号名>`。维护者账号由 `crane0927` 改名为 `crane199709` 后，仓库坐标改为 `io.github.crane199709`。[ADR 0012](0012-maven-coordinates-use-github-namespace.md) 的规则继续有效：坐标依赖 GitHub 命名空间归属，不依赖品牌域名的 DNS 控制权，也不得改用无法验证的坐标。

本次换坐标的前提是尚无任何已发布制品：Central 上 `io.github.crane0927` 与 `io.github.crane199709` 都没有制品，仓库也不存在触发发布的 `v*.*.*` 标签。因此本次变更不需要兼容层，不构成对使用者的破坏性升级；旧 `io.github.crane0927` 命名空间保留但不再发布任何版本。旧用户名已不可用，继续使用旧坐标无法再证明命名空间归属，这是必须改坐标而不是保留旧值的原因。

首个正式版本发布后坐标冻结，不得再改。此后若维护者账号再次改名，只能在新的可验证命名空间下发布新坐标，并按破坏性变更处理；发布白名单、BOM、Starter 与外部消费者夹具的同步迁移另行确认，不自动跟随账号改名。

坐标由根 POM 及其模块 POM 声明，发布边界门禁继续以字面量钉死当前公开坐标，使坐标漂移必然失败，而不是随根 POM 自动跟随。发布前必须在 Central Publisher Portal 注册并校验新命名空间，并确认 `CENTRAL_USERNAME`、`CENTRAL_PASSWORD` 对应可发布该命名空间的账号；步骤见 [Maven 构建与制品发布](../21-maven-build-and-release.md)。
