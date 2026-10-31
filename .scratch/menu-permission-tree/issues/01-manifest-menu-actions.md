# 01: Resource Manifest 叶子菜单新增 actions

**What to build:** 按 [spec](../spec.md) 第 2 节在 `ResourceManifest.MenuNode` 上新增可选的 `actions`，SDK 生成与 iam 接收共用校验，已上报的清单保持兼容；demo-service 补写示例。

**Blocked by:** 无

**Status:** resolved

- [x] `MenuNode` 新增 `actions`，缺省空列表，空时不输出（`@JsonInclude(NON_EMPTY)`）；`withChildren` 保留 `actions`；文档注释说明含义
- [x] `validateMenus` 校验：只有叶子可带 `actions`、权限码已在本清单声明、不重复、不含本节点 `permission`
- [x] 不带 `actions` 的清单序列化结果与改动前完全相同（测试覆盖：已存储的同版本清单重复上报仍为 200）
- [x] `AccessService` 返回的菜单去掉 `actions`
- [x] demo-service `manifest.json` 为「订单概览」补写 `actions`（`order:create`、`order:update`、`order:delete`），`forge.manifest.version` 升为 5
- [x] 更新 `ResourceManifestCollector` 文档注释中的清单文件格式示例
- [x] 相关测试：`ResourceManifestCollectorTest`、iam `ResourceManifestTest` 覆盖新校验规则

## Comments

## Answer

已实现叶子菜单 actions 的缺省、序列化与共享校验，保留旧构造方式；`withChildren` 保留 actions，`/auth/access` 去除 actions。demo 订单概览声明三个动作并将清单版本升为 5，SDK 文件格式示例同步更新。

验证：按 TDD 完成模型与序列化、非法 actions、access 返回字段的红→绿；SDK `ResourceManifestCollectorTest` 11 项、IAM `ResourceManifestTest` 16 项通过（使用隔离 PostgreSQL/Redis Testcontainers），包括历史 JSON 清单同版本重报 200、共享 action、四条校验与 access 去字段。13 个相关模块的架构测试通过，共 40 项，无失败或跳过。demo 编译及 Spotless 格式化通过；`git diff --check` 通过。

命令：`cd backend && ./mvnw -pl forge-iam,forge-sdk/forge-sdk-manifest,examples/demo-service -am spotless:apply test -Dtest=ResourceManifestTest,ResourceManifestCollectorTest,ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`。

未修改授权判定、数据库 schema 或现有环境数据；未部署服务。
