# 仓库与本地目录重命名验收

日期：2026-11-11

## 结果与范围

- GitHub 仓库：`crane199709/saas-forge-v2` → `crane199709/saas-forge`。仓库 ID 仍为 `1439325190`，默认分支 `main`，可见性仍为 private。
- 本地目录：`/Users/liuhuan/workspace/coding/saas-forge-v2` → `/Users/liuhuan/workspace/coding/saas-forge`。旧目录不存在，未创建兼容符号链接。
- `origin` 已更新为 `https://github.com/crane199709/saas-forge.git`。
- Actions variable `MAVEN_RELEASE_URL` 已更新为 `https://maven.pkg.github.com/crane199709/saas-forge`。
- Actions variable `IMAGE_PREFIX` 已按后续指示统一为 `ghcr.io/crane199709/saas-forge`，当前镜像示例与测试夹具使用同一前缀。
- 主工作区 HEAD 保留 `d81f2e9945745aed0ab1044b2fcf9010b6657448`，迁移前已有的 1 个未推送提交保留。
- 3 个嵌套 worktree 已通过 `git worktree repair` 修复，均保持干净和原分支、原 HEAD。
- IDEA 的项目路径、工具路径和项目模块名已更新；pnpm 已按原锁文件重新安装，工作区状态文件不再引用旧目录。
- 更新镜像/发布说明和镜像校验测试夹具，业务代码、Maven 坐标和 npm 包名保持原样。

## 本地服务与数据

- 先停止 Compose 容器，再完整移动目录。Git 目录及 PostgreSQL、Redis、Nacos、S3 数据目录的 inode 均保持不变。
- 所有 10 个容器从新路径重建；恢复原先运行的 7 个服务：postgres、redis、nacos、rocketmq-namesrv、rocketmq-broker、rocketmq-proxy、s3。
- 3 个一次性初始化容器保持 created，未重新执行，避免覆盖 Nacos 等现有配置。
- 所有容器挂载均无旧目录引用；PostgreSQL、Redis、Nacos、S3 的健康检查通过。
- PostgreSQL `iam`、`platform`、`demo` 共 74 张表的行数在迁移前后完全一致，行数清单 SHA-256：`68ad489c21884031b8ac0c10e95c7a4b7ee71d620d558d23c930dd8dbb4de2a6`。
- Redis PING 返回 PONG。

## 验证

- 新远程地址 `git ls-remote origin refs/heads/main refs/tags/v0.1.0` 成功。
- `backend/./mvnw -o -DskipTests compile`：26 个模块成功。
- `frontend/pnpm typecheck`：通过。
- `frontend/pnpm build`：通过。
- `python3 scripts/images/test-check-package.py`：7 个场景通过，使用统一后的镜像命名空间和仓库名称。
- `python3 -m unittest discover -s scripts/release -p 'test_*.py'`：最终 12 个测试通过。首轮沙箱禁止监听端口；提权后一次短超时用例因请求次数断言失败，编译结束后重跑通过，未修改无关测试。
- `git diff --check`：通过。
- CodeGraph 在新目录下读取现有索引成功，报告索引已同步。

## 尚需用户处理与验证边界

- Codex 本地项目登记仍为旧目录。现有工具不支持编辑项目路径；需在项目菜单的 Edit project 中将目录改为新路径，并将其设为主目录。IDEA 也需从新目录重新打开项目。
- Maven 真实制品下载尚未验证。本机 GitHub CLI 令牌缺少 `read:packages`；GHCR 已通过下述 Actions 迁移和完整拉取验证。

## 本地恢复资料

容器完整配置、原 IDEA/Git 配置、worktree 元数据、发布变量和数据库行数基线存于：

`/private/tmp/saas-forge-rename-20261111-134027`

备份目录权限为 0700；包含容器配置的文件权限为 0600。恢复资料未加入 Git。

## GHCR 名称统一补充验收（2026-11-11）

- 用户进一步确认将镜像命名空间统一为 `saas-forge`。远程 Actions variable `IMAGE_PREFIX` 已更新为 `ghcr.io/crane199709/saas-forge`。
- 当前文档、拉取示例和测试夹具均使用新前缀；当前配置和代码（历史验收记录、构建产物及日志除外）不再引用旧项目名称。
- [迁移 run 42971139681](https://github.com/crane199709/saas-forge/actions/runs/42971139681) 已 success，使用同一仓库现有的 `packages: write` 权限执行，无新增长期凭证。
- 迁移六个私有 Container 包，共 31 个历史版本。所有原有标签及 manifest digest 均保留；原本无标签的版本添加 `migrated-sha256-<digest>` 归档标签，以保留其 digest 访问能力。
- 六个 `0.1.0` 正式镜像均已从新命名空间完整拉取所有层并核对 manifest digest。
- 新包保持 private；旧包保留作为回退副本。历史镜像内容与原始构建元数据保持不变。
- 一次性工作流使用临时分支，提交为 `b9a45adad0c995c763e00bb745fdbaaf535e5b99`；执行成功后已清理该临时分支，远程 `main` 保持 `76ef3a286f8517bb5159619373c63ce25ca375d9`。
- 迁移清单见 [ghcr-migration-inventory.json](ghcr-migration-inventory.json)，新地址与 digest 逐项校验结果见 [ghcr-migration-verified.json](ghcr-migration-verified.json)，工作流摘要见 [ghcr-migration-summary.md](ghcr-migration-summary.md)。
- `python3 scripts/images/test-check-package.py` 的 7 个场景通过；`git diff --check` 通过。
