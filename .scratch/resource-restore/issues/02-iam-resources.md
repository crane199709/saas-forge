# 02: Role、Department、Position 恢复

**What to build:** 按 spec 第 3 节实现 IAM 三类资源的已删除列表和恢复接口。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] Role 删除不再连带删除授权和数据策略，已删除角色的配置不影响权限计算
- [ ] Role 恢复后授权和数据策略完整，且不授予任何成员权限
- [ ] Department 父部门已删除时被拒绝，父部门移动后恢复能得到正确路径
- [ ] 三类资源名称冲突时返回 `resource.restoreConflict`
- [ ] 恢复记录审计，跨租户恢复被拒绝，并发恢复只成功一次

## Comments
