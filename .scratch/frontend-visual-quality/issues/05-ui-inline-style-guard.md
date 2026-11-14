# 05: `@forge/ui` 内联样式强制校验

**What to build:** 给 `@forge/ui` 建一道机器校验，让「样式只写主题 token」这条约定第一次真的能被 CI 拦住。该包全部用 `h()` 内联样式，现有样式规则只扫 `.vue` 与 `.css`，是当前最大的规范盲区。见[规格 4.6](../spec.md#46-强制机制)。

**Blocked by:** 01、02、03、04（工具会扫全包，先让前面的改动落地再开规则，避免误报混进本次改动）

**Status:** resolved

- [x] 新增一个独立工具，用 TypeScript 编译器 API 静态扫描 `@forge/ui` 中 `h()` 调用里 style 对象的字面量色值与长度值；不用正则（AST 能区分样式字面量与注释里的值）
- [x] 工具纳入前端工具脚本的类型检查范围，并在前端工作区加一个 `lint:ui` 脚本
- [x] 前端 CI 在既有样式检查步骤之后新增该步骤
- [x] **只在 monorepo 内启用**，不进 `sub-app-kit` 的发布物；在 `frontend-design.md` 里写明「验证一个版本周期后再由 kit 依赖」的后续动作
- [x] 不修改既有的 `.vue`/`.css` 样式规则，本次只补 `h()` 这个盲区
- [x] 修掉首次运行报出的既有违规，不改动视觉结果（若某处必须保留字面量，在工具里加显式豁免而不是放宽规则）
- [x] 误报处理：若 AST 对某些写法判断不准，先放宽为 warning 并在 `frontend-design.md` 记录待办，**不退回复正则方案**
- [x] 测试：工具自身的单元测试覆盖「该报的报、不该报的不报」（`var()`、`color-mix()`、常量引用不报；hex、`rgb()`、`px` 报）
- [x] `pnpm lint:ui`、`pnpm lint:style`、`pnpm typecheck`、`pnpm -r test` 通过，且视觉基线无变化

## Comments

**2026-11-14 合并与验收**

提交：`1f5cbfc8` 工具与 CI 接线、`8b2804ef` 修既有违规、`e1abdd66` 合并集成分支，合并到集成分支为 `697fe2f0`。

验证：`lint:ui`（0 error / 0 warning）、`lint:style`、`pnpm -r typecheck && pnpm typecheck:tools`、`format:check`、串行全量测试、视觉 215 张全过且像素未变。

首次运行报 11 处 error，全部是长度字面量、无颜色字面量：7 处映射到既有 `--forge-*` 档位；4 处取同值的 Element Plus 变量（`--el-border-width`、`--el-font-size-large`、`--el-component-size-large`）；1 处（`ResultPage` 上下 80px）无对应档位、仅一处，用 `// style-guard-ignore:` 显式豁免并写明「出现第二处时提为 token」。没有为此新增 token 档位，也没有放宽规则。

## 推翻了一条我下发的错误假设

我在任务里写「Vue 会把组件样式对象里的裸数字当 px 用」，实现者用 jsdom 实测 `vue@3.5.44` 的 `setStyle` **不会补 px**，得到的是空 width。于是改为：16 个无单位属性（`fontWeight`/`lineHeight`/`flex*`/`opacity`/`zIndex` 等）白名单内合法，长度属性上的裸数字降为 warning 并记为待办。首轮扫出的 6 处裸数字全是白名单内的正确写法，零噪声。这是「先验证再采纳」而不是照抄指令，结论已进 `frontend-design.md`。

## 发现

基线目录有 **229 个 PNG，但实际上只有 215 个测试**，即 14 张从未被任何用例引用。这些是历史遗留，与本次无关，已另开[工单 07](07-prune-unreferenced-baselines.md)。
