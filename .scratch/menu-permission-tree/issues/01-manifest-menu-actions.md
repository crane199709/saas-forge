# 01: Resource Manifest 叶子菜单新增 actions

**What to build:** 按 [spec](../spec.md) 第 2 节在 `ResourceManifest.MenuNode` 上新增可选的 `actions`，SDK 生成与 iam 接收共用校验，已上报的清单保持兼容；demo-service 补写示例。

**Blocked by:** 无

**Status:** ready-for-agent

- [ ] `MenuNode` 新增 `actions`，缺省空列表，空时不输出（`@JsonInclude(NON_EMPTY)`）；`withChildren` 保留 `actions`；文档注释说明含义
- [ ] `validateMenus` 校验：只有叶子可带 `actions`、权限码已在本清单声明、不重复、不含本节点 `permission`
- [ ] 不带 `actions` 的清单序列化结果与改动前完全相同（测试覆盖：已存储的同版本清单重复上报仍为 200）
- [ ] `AccessService` 返回的菜单去掉 `actions`
- [ ] demo-service `manifest.json` 为「订单概览」补写 `actions`（`order:create`、`order:update`、`order:delete`），`forge.manifest.version` 升为 5
- [ ] 更新 `ResourceManifestCollector` 文档注释中的清单文件格式示例
- [ ] 相关测试：`ResourceManifestCollectorTest`、iam `ResourceManifestTest` 覆盖新校验规则

## Comments
