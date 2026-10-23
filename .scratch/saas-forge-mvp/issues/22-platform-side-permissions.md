# 22: 平台侧权限

**What to build:** 平台侧本身是一套完整的管理系统。platform 服务像 Business Service 一样上报 Resource Manifest；Platform Realm 内置「超级管理员」；超级管理员在 platform-console 管理 Operator 账号与平台角色，为角色授予权限码与数据范围，使 Operator 的平台功能与平台数据（如租户列表）受 RBAC + ABAC 约束。

**Blocked by:** 08 Platform Realm 与运营登录, 16 租户角色管理, 18 数据权限端到端（Data Scope 预设）

**Status:** ready-for-agent

- [ ] platform 服务的接口声明权限码并上报清单
- [ ] 内置超级管理员角色，不可删除
- [ ] platform-console 提供 Operator 账号管理（创建、停用、重置密码）与平台角色管理
- [ ] 平台角色可配置数据范围（如按 Operator 负责的区域或自定义条件限定可见租户）
- [ ] 平台侧权限与租户侧权限数据完全分离，共用同一套引擎
- [ ] Operator 停用或角色变更即时生效
- [ ] 测试：受限 Operator 只能看到其范围内的租户；无权限操作被拒
