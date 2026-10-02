# Nacos 受控配置清单

`deploy/nacos/<environment>/` 是 Nacos 非敏感配置的唯一权威来源。`dev`、`test`、`staging`、`prod` 各有独立目录，且每个目录必须且只能包含 `SAAS_FORGE` group 的七个配置资源：

- `gateway.yaml`
- `iam-service.yaml`
- `tenant-access-service.yaml`
- `entitlement-service.yaml`
- `audit-service.yaml`
- `remote-delivery-service.yaml`
- `project-service.yaml`

目录和 Nacos namespace 一一对应；清单不引入共享资源。应用的 `application.yaml` 只导入同名资源，Nacos 连接地址、namespace 和工作负载凭据继续由 Helm values 或环境变量提供。生产连接参数和外部 Secret 引用受 [`../helm/nacos-production-contract.yaml`](../helm/nacos-production-contract.yaml) 约束。

## 校验与发布

提交前和 `Verify` CI 都执行：

```bash
bash scripts/validate-nacos-config.sh
```

该脚本校验环境和资源完整性、YAML 结构、应用专属 `configuration-revision` 标记，并拒绝密码、密钥、令牌、凭据以及 Nacos 连接参数进入清单。

`staging` 和 `prod` 清单只声明 gRPC 必须启用 TLS，以及 IAM、Tenant Access 分别引用
`tenant-access-grpc-client`、`tenant-access-grpc-server` SSL Bundle。证书、私钥和 CA
不进入 Nacos；部署系统必须通过 Helm values 或外部 Secret 挂载的 Spring 配置提供这两个
Bundle。Bundle 缺失时应用应启动失败，不能回退到明文通信。

测试及部署环境发布从 `Publish Nacos configuration` GitHub Actions 工作流触发。当前本地开发环境可复用 `nacos-init` 同步 `dev` 清单，凭据核对、备份、校验、读回和重启步骤见[开发配置说明](../../docs/development-configuration.md#更新当前开发-nacos)。本地同步不会自动启停应用。工作流须选择目标 GitHub Environment（`dev`、`test`、`staging` 或 `prod`），再以该 Environment 注入的下列值运行：

- `vars.NACOS_SERVER_ADDR`：目标 Nacos HTTPS 地址；
- `secrets.NACOS_PUBLISH_USERNAME`、`secrets.NACOS_PUBLISH_PASSWORD`：该环境的独立配置发布身份。

工作流调用：

```bash
bash scripts/publish-nacos-config.sh <environment>
```

脚本先重跑校验，只向同名 namespace 的 `SAAS_FORGE` group 发布七个资源，并在 Nacos 配置历史中写入 Git commit、工作流和运行编号；同时将每份配置的 SHA-256 写入 GitHub Actions Summary。`prod` GitHub Environment 必须启用部署保护与审批。回滚使用已验证的 Git commit 重新运行同一工作流，不能以 Console 修改替代发布。

## 最小权限

每个 namespace 都使用下列不共享的角色；资源格式是 Nacos 默认鉴权插件的 `<namespace>:<group>:<type>/<name>`：

| 身份 | 配置权限 | 服务发现权限 |
| --- | --- | --- |
| `gateway-<environment>` | 读 `gateway.yaml` | 写 `gateway`；读 `iam-service`、`tenant-access-service`、`entitlement-service`、`remote-delivery-service`、`project-service` |
| `iam-service-<environment>` | 只读 `iam-service.yaml` | 写自身；读 `tenant-access-service` |
| `tenant-access-service-<environment>` | 只读 `tenant-access-service.yaml` | 写自身；读 `iam-service`、`entitlement-service` |
| `entitlement-service-<environment>` | 只读 `entitlement-service.yaml` | 写自身；读 `iam-service`、`tenant-access-service` |
| `audit-service-<environment>` | 只读 `audit-service.yaml` | 写自身 |
| `remote-delivery-service-<environment>` | 只读 `remote-delivery-service.yaml` | 写自身；读 `iam-service`、`tenant-access-service` |
| `project-service-<environment>` | 只读 `project-service.yaml` | 写自身；读 `iam-service` |
| `config-publisher-<environment>` | 只写七个已声明配置资源 | 无 |
| `breakglass-config-<environment>` | 只写七个已声明配置资源 | 无 |

各角色还可按既有就绪/验收要求读取自身实例。各环境上线统一发现前，须确认上述跨服务 naming 读取权限；本次不自动修改远端环境 ACL。工作负载角色不得有配置写权限、`console/*` 权限或通配符权限。发布角色不拥有服务注册/发现权限；生产角色的凭据只能注入受保护 GitHub Environment 的发布工作流。`nacos-init` 在本地 Compose 中按该矩阵创建 `dev` 身份，已有五个工作负载由 CI 验证；新增两个身份由阶段 3 隔离验收验证，未初始化的环境不会自动开通身份。

## Console 应急与回写

Console 默认只读。紧急处置只能使用独立的 `breakglass-config-<environment>` 身份，并且应在操作前记录变更单与目标资源。操作时记录 Nacos 配置历史的操作者、时间、版本和差异；密钥、密码和令牌不得作为应急配置写入。

应急结束前，操作者必须将相同变更提交为 Git Pull Request，关联变更单，并通过 `Publish Nacos configuration` 将审查后的清单重新发布。若 Console 状态与 Git 不同，Git 清单为权威来源，回写后的发布结果和 Actions Summary 是完成对账的证据。
