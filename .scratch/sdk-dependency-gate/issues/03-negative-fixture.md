# 03: 负向 fixture 与依赖文档

**What to build:** 按 [spec](../spec.md) 的“实现决策”，新增负向 fixture 模块证明门禁会使构建失败，并整理白名单/豁免文档。

**Blocked by:** 01, 02

**Status:** ready-for-agent

- [ ] 负向 fixture 放在独立 profile 下，故意声明被禁止的依赖
- [ ] CI 在指定 profile 下构建 fixture 并断言失败
- [ ] 正向 fixture 或现有模块构建成功，证明门禁不误伤
- [ ] 文档列出每个 SDK 模块允许的依赖、平台豁免与原因
- [ ] 测试覆盖：负向失败、正向成功、豁免模块成功
- [ ] fixture 不进入默认 reactor，避免拖慢日常构建

## Comments
