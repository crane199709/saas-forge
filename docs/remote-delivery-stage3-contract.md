# 阶段 3 Remote Delivery 最小契约

状态：独立服务归属已确认（[ADR 0055](adr/0055-remote-delivery-owns-manifest-lifecycle.md)）；下述最小授权边界已于 2026-10-02 经用户确认，最小实现与同轮浏览器验收已完成，见 [阶段 3 验收](acceptance/stage3-browser-acceptance.md)。

Remote Delivery 独立拥有逻辑数据库与迁移。Manifest 的注册声明不可变，注册、审核、启用与拒绝均在来源事务中保存操作者和时间事实。Gateway 从正式 OpenAPI 生成路由并经 Nacos 发现该服务；不保存或裁决 Manifest 状态。

## 公开操作与权限

| 操作 | 凭据与权威检查 | 行为 |
| --- | --- | --- |
| `POST /api/v1/remote-manifests` | CI Client Credentials；`remote-delivery:manifest:register`；服务复验签名与撤销状态 | 注册不可变模块版本，状态 `PENDING_REVIEW`；同声明重试返回原结果，不同声明冲突 |
| `GET /api/v1/platform/remote-manifests` | 平台上下文用户 Token；IAM 当前 `PLATFORM_ADMIN` | 分页查看注册结果与当前审核/启用状态 |
| `POST /api/v1/platform/remote-manifests/{id}/approve` | 同上 | `PENDING_REVIEW → APPROVED`，保存审核事实 |
| `POST /api/v1/platform/remote-manifests/{id}/reject` | 同上 | `PENDING_REVIEW → REJECTED`，保存拒绝事实；不可启用 |
| `POST /api/v1/platform/remote-manifests/{id}/enable` | 同上 | `APPROVED → ENABLED`；同模块首版只允许一个启用版本，已有启用版本时拒绝替换 |
| `GET /api/v1/remote-manifests/enabled` | Starter 复验签名/撤销状态并提供 Tenant Context；服务经 Tenant Access 实时复验 Membership/Tenant 联合可用性 | 仅返回启用的受控 Manifest，分页；没有启用项时为空集合 |

审核操作具有持久化幂等键与操作者绑定；同键不同请求拒绝。未知提交结果先读权威状态，不能盲目生成新键重试。注册唯一键是 `(module, version)`；启用并发由数据库约束保证。完整禁用、升级、回退仍在阶段 7。

## 注册声明

- `module`：小写稳定模块标识，首个官方模块 `project`。
- `version`：固定版本，首版使用严格三段数字版本；禁止路径分隔符、查询与片段。
- `source`：必须精确等于部署根域推导的 `https://remote.<root>/<module>/<version>/remote.js`；不能修改 Origin、端口或注入用户信息、编码路径、查询与片段。
- `uiVersion`：Shell 的统一 UI 制品版本，Shell 在执行脚本前校验一致性。
- `entrySha256`：入口 JavaScript 的 SHA-256，Shell 在无凭据下载后、执行前验证；同版本发布资源保持不可变。

注册主体只能注册部署期授权给该 CI Client ID 的模块；未绑定模块默认拒绝。客户端不得提交或覆盖状态、审核者、启用者和对应时间。Shell 的共享组件、Locale、品牌与业务 operation 通过受限 Host 接口交给 Remote；接口不提供 Token、底层通用 fetch 或凭据参数。

## 已确认的安全增量

IAM 增加 `CI_CLIENT` 类型；它只允许 `remote-delivery:manifest:register`，不能混用 Runtime 或内部服务 Scope。仅 Platform Administrator 可创建/轮转/吊销该类型 Client，复用既有一次性 Secret、恢复与撤销协议；已有 Runtime Client 的授权保持不变。

Remote Delivery 增加一个受控 Reserved Service 身份，精确拥有 `iam:platform-role:read`、`tenant-access:membership:read` 和 `tenant-access:tenant:read`，用于当前平台角色与租户上下文的权威校验。Gateway 只新增上述公开路由及对应服务发现读取权限，CI 注册 Scope 仅适用于注册 operation。新服务的 Nacos 配置、数据库角色、Kafka/审计投递权限与静态资源路径按最小权限配置；实际环境发布另走受控流程。

首版不授予 CI 审核、启用、查询租户业务数据或扩大浏览器 Origin 的能力。所有 Secret 沿用受限文件/Secret 注入，不进入 Nacos、Manifest、浏览器或证据。

## 最终验收

同轮 Fresh 环境中，CI 注册 → Chrome 平台上下文审核与启用 → 租户 Shell 验证 Manifest 和制品 → Project/Task Remote 通过正式 Client 与 Gateway 完成 CRUD；验证跨 Tenant 拒绝、缺失 Context 拒绝、未审核/未启用/来源异常/加载失败与恢复、Locale 传递，以及同轮结构化日志、真实 Trace 和六种成功事实入 Audit。API 诊断、种子数据、类型检查或静态夹具通过均不能替代这条产品路径。
