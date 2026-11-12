# 02: SDK 模块级依赖白名单

**What to build:** 按 [spec](../spec.md) 的“实现决策”，每个 SDK 模块声明允许的直接依赖白名单，Enforcer 在 `validate` 阶段校验。

**Blocked by:** —

**Status:** claimed

- [ ] SDK 父 POM 配置 Enforcer：`bannedDependencies` 以模块白名单为 `includes`，`searchTransitive=false`
- [ ] 每个模块在 POM 声明允许的直接依赖；新增依赖必须更新白名单
- [ ] 覆盖 optional 与“声明但未 import”的依赖
- [ ] 非法依赖失败信息指向模块与依赖，并提示更新白名单
- [ ] ArchUnit 的 `sdkDependencies`、`sdkCycles` 继续保留，不重复实现
- [ ] 测试覆盖：白名单内成功、白名单外失败、optional 声明失败、传递依赖不误拦

## Comments
