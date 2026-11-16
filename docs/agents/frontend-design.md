# 前端设计规范（平台内部）

页面流程、原型、组件、交互、样式与走查清单见 [Sub-App 页面规范](../standards/sub-app.md)，monorepo 内的应用同样遵守。本文只记录平台内部的补充规则。

视觉参考是 [soybean-admin-element-plus](https://github.com/soybeanjs/soybean-admin-element-plus)（MIT），借鉴它的信息结构和克制的样式，代码按本仓库约定重写。线上样板是租户端 `/roles`（`apps/tenant-app/src/role/`）：新页面照它的结构写。

## 设计走查

- 在 `frontend/` 下运行 `pnpm test:visual:update`，用例在 `frontend/e2e/visual/`，截图在 `frontend/e2e/visual/__screenshots__/`，用 Read 逐张查看。
- 基线只在 macOS 生成。

## 样式

- `packages/ui/src/theme.ts` 的 `layoutTokens` 定义 `--forge-*` token。
- token 分三层，改动落在唯一归属的层（决策见 ADR-0029）：
  - primitive：`layoutTokens` 里的间距、圆角、字号、阴影、层叠与字体族档位，以及 `themeTokens` 的主色；由 Shell 或独立开发宿主通过统一主题入口写到文档根节点。
  - semantic：Element Plus 的 `--el-color-*`、`--el-text-color-*`、填充变量（如 `--el-fill-color-light`）及尺寸与描边变量（如 `--el-font-size-large`、`--el-component-size-large`、`--el-border-width`），不另建 `--forge-color-*` 语义色。
  - component：组件私有变量，只在自身选择器或自身元素的 `style` 上定义，如菜单元素的变量覆盖、`EmptyState` 的 `--forge-empty-icon-size`；不写到文档根节点。用 `h()` 的组件没有选择器，取值与 primitive 一起放在 `theme.ts`（token 的唯一来源），但只由该组件写到自己的元素上。
- 加档位的判断标准：现有档位确实不够用，且新值不替代任何现有档位时才追加，旧名保留，布局档位取值不改。平台默认配色允许在 CHANGELOG 明确说明并完成视觉验收后统一演进。原始数值进 primitive，角色色进 semantic，只有单个组件使用的取值进 component。
- `packages/ui` 用 `h()` 的内联样式，stylelint 覆盖不到，改由 `pnpm lint:ui` 检查（见下）。

默认浅色主题与升级约定见 Sub-App 规范「统一浅色主题」。默认配色集中在 `packages/ui/src/theme.ts`，宿主调用 `applyTheme()` 并载入 `@crane199709/ui/theme.css`；诊断页 alternate 探针只变主色，中性色保持统一，不作为产品主题入口。

## 内联样式检查（lint:ui）

- `frontend/packages/ui/scripts/lint-ui-style.ts` 用 TypeScript 编译器 API 遍历 `packages/ui/src/**/*.ts` 的 `style` 对象，报出字面量色值（hex、`rgb()/hsl()/rgba()/hsla()`）与长度字面量（`px`、`rem`、`em`）。不用正则，AST 才能区分样式字面量与注释、普通属性、常量引用里的同名文本。
- 扫描范围是 `packages/ui/src/**/*.ts` 去掉 `*.spec.ts` 与 `test-mount.ts`：测试夹具里的样式不是产品样式，扫进来只会要求给夹具造 token。
- 允许：`var(--*)`、`color-mix()`、`calc()`/`min()`/`max()`、百分比、`0`，以及无单位语义属性的纯数字（`fontWeight`、`lineHeight`、`flex`、`opacity`、`zIndex` 等，清单在工具里）。函数里的 `px/rem/em` 仍算字面量，与 stylelint 口径一致。
- `packages/ui/src/theme.ts` 是 token 的唯一来源，**必须**排除：里面的字面量是定义不是违规。不要以「统一扫描」为由加回扫描范围。
- Vue 3 不给数值补 `px`，长度属性写裸数字等于无效样式，工具先报 warning 不阻断；待确认没有历史依赖后再升级为 error。已知未覆盖：常量引用与带插值的模板字符串不展开（不做跨文件常量求值）。
- 确实没有对应 token 且只有一处使用时，在属性上一行写 `// style-guard-ignore: 理由` 显式豁免，并在理由里写清何时提为 token；优先用现有 token，没有匹配档位时取 Element Plus 的尺寸与描边变量（如 `--el-font-size-large`、`--el-component-size-large`、`--el-border-width`）。
- 该工具**只在 monorepo 内启用**，不随 `sub-app-kit` 发布：外部生成工程同时装新版 kit 时 CI 会一起变红，风险不对等。验证一个版本周期、误报清零后，再由 `sub-app-kit` 依赖它并在生成的 Sub-App 里启用。

## 视觉基线

- 基线只在 macOS 生成并比对，团队固定同一平台；换平台重跑会因字体渲染差异误报。
- `maxDiffPixelRatio` 为 0.001，在 1024×768 / 1440×900 上分别允许约 786 / 1296 个差异像素，可以拦住[工单 08](../../.scratch/frontend-visual-quality/issues/08-baseline-drift-under-threshold.md)记录的 0.13%–0.31% 局部漂移。它仍可能漏过整图 0.1% 以下的变化，关键布局状态另用 DOM 断言覆盖。
- [工单 09](../../.scratch/frontend-visual-quality/issues/09-eliminate-render-nondeterminism.md)的服务凭证两态来自 Playwright 点击固定列按钮时的自动滚动：背景表格的 `scrollLeft` 在 0 与 160 之间变化，弹窗的实际字体与位置相同。对于本来就在视口内的目标，用 `click({ scroll: 'none' })` 并断言背景滚动位置；需要滚动才能到达的目标仍使用正常点击。
- 自动展开的菜单须先确认分组为 `is-opened`，再用视觉测试的 `expectExpandedMenusSettled` 等待 `max-height: none` 后截图。Element Plus 展开过程中的 `overflow: hidden` 会包含子项外边距，过渡结束后外边距折叠，造成 4px 的位置差异；仅开启截图的 `animations: 'disabled'` 不能替代这一状态检查。
- 公告到期等随时间变化的截图，用 `page.clock.setFixedTime` 固定业务时间，并断言目标业务状态，避免执行日期改变后把过期状态写进原本覆盖“发布中”的基线。
- 弹窗打开或内容替换后，原点击位置可能落在新出现的控件上；需要覆盖非悬停态时，截图前用 `page.mouse.move(0, 0)` 移开指针。关键颜色另做 CSS 断言：截图的单像素颜色容差可能放过一整段文字的悬停色变化。
- 排查两态时先留存两张原图，核对差异区域的几何位置、滚动位置、计算样式及实际字体。不能仅凭文字看起来偏粗就判断为字体回退，也不通过提高容差掩盖页面状态差异。
- 重写基线必须用 `--update-snapshots=all`：默认的 update 会按容差跳过小变化，导致基线实际没更新。首次变更逐张走查并说明原因，后续连续五轮逐张比较 PNG 哈希；同一构建下应保持一致，仍有差异须说明原因。临时采样脚本、原图与日志放在仓库外。
- 带同一套基线跑 `--repeat-each=3` 验证重复执行；重生成后的基线与代码一同提交，说明实际变化及原因。
- 工单 09 的五轮全量对比中，状态修正后的 13 张图后四轮逐字节一致；其余仍有圆角/阴影边缘每张 1—14 个像素、单通道 1—3 个色阶的绘制尾差。哈希不同时先量化像素数、位置与单通道差异，并留存证据；本次这些尾差保留原基线，不能据此忽略控件颜色或布局状态的变化。
