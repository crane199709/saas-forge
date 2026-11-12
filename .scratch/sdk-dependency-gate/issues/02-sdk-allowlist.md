# 02: SDK 模块级依赖白名单

**What to build:** 按 [spec](../spec.md) 的“实现决策”，每个 SDK 模块声明允许的直接依赖白名单，Enforcer 在 `validate` 阶段校验。

**Blocked by:** —

**Status:** resolved

- [x] SDK 父 POM 配置 Enforcer：`bannedDependencies` 以模块白名单为 `includes`，`searchTransitive=false`
- [x] 每个模块在 POM 声明允许的直接依赖；新增依赖必须更新白名单
- [x] 覆盖 optional 与“声明但未 import”的依赖
- [x] 非法依赖失败信息指向模块与依赖，并提示更新白名单
- [x] ArchUnit 的 `sdkDependencies`、`sdkCycles` 继续保留，不重复实现
- [x] 测试覆盖：白名单内成功、白名单外失败、optional 声明失败、传递依赖不误拦

## Comments

## Answer

SDK 父 POM 在 validate 阶段全禁并按 forge.sdk.allowedDependencies 放行，各17模块已逐项声明允许的直接依赖。缺省名单为空；optional、test、provided 声明同样受检；不搜索传递依赖。现有 ArchUnit 规则保留。实现 74a4444，合并 1cc9ec3。

验证：SDK 父和17个子模块 validate / Spotless 全通过；8项 Maven 行为样例覆盖逗号多行名单成功、名单外/漏配/optional/test/provided/非SDK平台依赖失败、Redis starter传递成功。持久fixture与完整文档表由工单03补齐。
