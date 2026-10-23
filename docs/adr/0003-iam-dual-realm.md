# 身份服务分 Platform / Tenant 两个 Realm，Operator 与 Identity 分离

平台侧与租户侧都需要完整的用户、角色、菜单与 ABAC，因此由同一个 `iam-service` 承载、共用一套权限引擎代码；但 Operator 与 Identity 账号分表存储，Token 使用不同 issuer，网关按路由只接受对应 issuer 的 Token，避免平台特权经 Membership 泄露到业务侧。Operator 若需以普通用户身份进入租户，需另有 Identity。第一期 Operator 不能访问租户业务数据（只管理租户元数据）；「租户授权运维」列为后续需求。

## Considered Options

- 把平台当作保留的「系统租户」：实现最省，但运营账号混入 Identity 体系，权限边界模糊。
- 平台侧单独一套 IAM：两套引擎重复开发。
