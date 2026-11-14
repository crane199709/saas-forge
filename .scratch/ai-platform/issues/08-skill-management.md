# 08: Skill 目录与租户启用

**What to build:** 按 [spec](../spec.md) 的“实现决策”，实现 Skill 目录、租户启用与 runner 物化。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 平台级 Skill 目录：编码、名称、说明、版本、文件内容/引用、状态
- [ ] 租户启用/停用 Skill；不能上传任意 Skill 代码（v1）
- [ ] 会话启动时把启用 Skill 物化到租户 DSH home 的 skills 目录
- [ ] 变更写审计；停用后新会话不再加载
- [ ] 测试覆盖：启用/停用、物化内容、权限、跨租户隔离、停用后新会话不可见

## Comments
