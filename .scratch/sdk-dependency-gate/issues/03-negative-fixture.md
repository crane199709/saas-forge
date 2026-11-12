# 03: 负向 fixture 与依赖文档

**What to build:** 按 [spec](../spec.md) 的“实现决策”，新增负向 fixture 模块证明门禁会使构建失败，并整理白名单/豁免文档。

**Blocked by:** 01, 02

**Status:** resolved

- [x] 负向 fixture 放在独立 profile 下，故意声明被禁止的依赖
- [x] CI 在指定 profile 下构建 fixture 并断言失败
- [x] 正向 fixture 或现有模块构建成功，证明门禁不误伤
- [x] 文档列出每个 SDK 模块允许的依赖、平台豁免与原因
- [x] 测试覆盖：负向失败、正向成功、豁免模块成功
- [x] fixture 不进入默认 reactor，避免拖慢日常构建

## Comments

## Answer

已实现并合入集成分支。`backend/src/it/sdk-dependency-gate/` 包含 7 个 fixture 与 `check.py`，通过 `sdk-dependency-gate` profile 加入 reactor，默认构建不包含它们。各样例直接继承源码父 POM；脚本以真实 Maven 退出码及具体 execution、规则、模块、依赖、修复提示判断结果，拒绝把下载或父 POM 解析错误算作负例通过。

原 `backend-quality.yml` 在 install 后执行同一脚本并归档失败日志。`docs/sdk-dependency-gate.md` 完整列出 17 个 SDK 模块允许的直接依赖与各 SDK/平台模块的豁免状态及原因。实现提交 `a6b6954`，合并提交 `ade0cf2`。

验证（2026-11-12）：15 项持久构建行为检查全部通过；临时禁用中间件门禁、放开 SDK 白名单的两个退化实验都被脚本拒绝，空缓存解析失败也不被误判为成功；默认 26 模块 validate、8 项目 Spotless、10 项 CI 范围选择测试、Prettier 与 git diff --check 通过。合并后由主代理复验完整 15 项脚本和默认 26 模块 validate，均通过。

独立 Standards / Spec 审查均为 0 项发现。远程 CI 未触发，本次未重跑无关业务测试。
