# 前端设计规范（平台内部）

页面流程、原型、组件、交互、样式与走查清单见 [Sub-App 页面规范](../standards/sub-app.md)，monorepo 内的应用同样遵守。本文只记录平台内部的补充规则。

视觉参考是 [soybean-admin-element-plus](https://github.com/soybeanjs/soybean-admin-element-plus)（MIT），借鉴它的信息结构和克制的样式，代码按本仓库约定重写。线上样板是租户端 `/roles`（`apps/tenant-app/src/role/`）：新页面照它的结构写。

## 设计走查

- 在 `frontend/` 下运行 `pnpm test:visual:update`，用例在 `frontend/e2e/visual/`，截图在 `frontend/e2e/visual/__screenshots__/`，用 Read 逐张查看。
- 基线只在 macOS 生成。

## 样式

- `packages/ui/src/theme.ts` 的 `layoutTokens` 定义 `--forge-*` token。
- token 分三层，改动落在唯一归属的层（决策见 ADR-0029）：
  - primitive：`layoutTokens` 里的间距、圆角、字号、阴影、层叠与字体族档位，以及 `themeTokens` 的主色；只有这一层由 Shell 写到文档根节点。
  - semantic：Element Plus 的 `--el-color-*` 与 `--el-text-color-*`，不另建 `--forge-color-*` 语义色。
  - component：组件私有变量，只在自身选择器内定义，如菜单元素的变量覆盖；不写到文档根节点。
- 加档位的判断标准：现有档位确实不够用，且新值不替代任何现有档位时才追加，旧名与取值不改。原始数值进 primitive，角色色进 semantic，只有单个组件使用的取值进 component。
- `packages/ui` 用 `h()` 的内联样式，lint 覆盖不到，同样只写 `var(--forge-*)`。
