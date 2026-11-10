# 04: Plan 编辑对话框改为按 Sub-App 的授权树

**What to build:** 按 [spec](../spec.md) 第 5 节，让 Operator 在 Plan 中先选可用 Sub-App，再按菜单页面勾选这些 Sub-App 的权限码；切换完成后移除旧接口并更新文档。

**Blocked by:** 02, 03

**Status:** ready-for-agent

- [ ] 「可用 Sub-App」放到权限选择之前，下拉框的选项与可输入未登记编码的行为不变
- [ ] 打开对话框时读取 `GET /platform/plans/permission-tree`，读取失败时沿用现有错误提示、不打开对话框
- [ ] 实现计算显示树的纯函数：所选 Sub-App 的叶子菜单各为一个根，分组节点不显示，多层分组拍平，按清单顺序排列；切换 Sub-App 不重新请求
- [ ] 使用共用组件的「页面权限作为子项」方式：页面下依次为页面的 `permission`、页面的 `actions`
- [ ] 选了多个 Sub-App 时，根节点旁显示 Sub-App 编码；未选 Sub-App 时菜单树为空并显示提示
- [ ] 共用提示只列当前显示的页面
- [ ] 「功能权限」区始终显示 `others`
- [ ] 「所属 Sub-App 未选择」区显示已勾选但归属的 Sub-App 全部未选中的码，保持勾选、可逐项取消；选上对应 Sub-App 后回到树中并保持勾选
- [ ] 「已下线」区保持现有行为
- [ ] 取消 Sub-App 时移除只归属它的码，保留仍归属其他已选 Sub-App 的码；重新选回时默认不勾选；「所属 Sub-App 未选择」区里的码不受影响
- [ ] 提交时三个分区的码去重后一起提交，沿用现有创建和更新接口
- [ ] 删除 `GET /platform/plans/permissions` 及前端调用，更新 `PlanTest`；`GET /internal/permissions` 与服务层目录保留
- [ ] 组件测试覆盖 spec 第 7 节 Plan 的全部场景，文案走 i18n
- [ ] 更新 `docs/sub-app.md` 的「发布到租户 Shell」

## Comments

