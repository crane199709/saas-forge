# 06: 稳定 onboarding 视觉用例的冷启动抖动

**What to build:** 消除 `onboarding.spec.ts` 中 `laptop › onboarding mixed` 的冷启动抖动，使视觉测试在 CI 的冷缓存环境下也能稳定通过。

**Blocked by:** 无

**Status:** resolved

## 现象

该用例的失败有稳定规律，不是随机噪声：

| 观察 | 次数 |
|---|---|
| 合并后的**首次**全量视觉测试失败，后续全量通过 | 3 次（工单 01、02 合并后各一次，以及主代理验证时一次） |
| 强制重跑后通过 | 6 次以上（含连续 5 次单跑全部通过） |
| 失败时的像素差 | 恒为 **12409 px**，ratio 0.02，1024×768 视口 |
| 差异形态 | 集中在文字栅格，呈亚像素级整体偏移；非布局错位、非内容缺失 |

## 为什么不是"可以忽略的抖动"

CI 的浏览器字体缓存**永远是冷的**。如果首次运行必失败，那么合并到主线后 CI 会稳定变红，而不是偶发变红。当前把它当作"重跑即可"处理，在本地是合理的，在 CI 上是掩盖问题。

## 已排除

- **不是基线过期**：如果基线过期会每次都失败，实际重跑即过。
- **不是工单引入**：工单 01 与 02 都验证过，页面与它们改动的侧栏图标、空状态无关联。
- **不是断言写错**：差异来自渲染，不来自测试逻辑。

## 待查方向（未验证）

1. 首次运行与后续运行的环境差异：`pnpm test:visual` 每次都会先构建三个应用，区分「新建 `node_modules` 后首次构建」与「增量构建」能否复现。
2. 该页面在 1024 宽度下是否处于「刚好出现/消失滚动条」的临界点——若是，15px 的宽度变化足以造成整页文字重排，且与字体度量精度相关。
3. `document.fonts.ready` 之后是否仍有 CJK 字体回退切换；Playwright 的等待是否覆盖到位。
4. 是否存在平台端（5183）预览服务器冷启动导致的资源版本不一致。

## 验收标准

- 在**删除 `node_modules` 并重新安装**后，连续两次全量视觉测试均 209 passed。
- 不通过放宽 `maxDiffPixelRatio` 或删除该用例来"解决"。

## Comments

**2026-11-14 根因更正与修复**

提交：`130cb147` 代码与测试。

### 真实根因不是 token 注入时机

按原假设给三个宿主的密度 CSS 补上 `var(--forge-font-size-sm, 14px)` / `var(--forge-space-2, 8px)` 后，全量视觉仍以同样的 **12405 px** 失败，说明 token 时序不是这个 flake 的原因。逐帧测量后确认：

- 失败画面与基线的字高、字距、各列宽度都一致；差异是**整张表（表头 + 数据行 + 展开区）统一左移 13px**。原先"列宽与字体不匹配"的判断来自这个整体位移。
- 该页表格的列 `min-width` 合计 813px，1024 视口下内容区宽 740px，本身就横向溢出（基线的横向滚动条也在）。Element Plus 的横向滚动容器 `.el-scrollbar__wrap` 在基线里是 `scrollLeft = 0`。
- 用 `locator('.el-table__expand-icon').first().click()` 时，Playwright 会把嵌套滚动容器中的目标"滚进视口"，把 `scrollLeft` 推成 **13**；`headerWrapper` 同步为 13，截图就是 12405 px 的差。`el.click()`、`dispatchEvent('click')`、`page.mouse.click()` 都不会滚动，`scrollLeft` 恒为 0。
- 因此这是 Playwright 点击动作对横向溢出容器的副作用，不是运行时 token 或字体加载竞态；是否滚动与这次点击的执行时机有关，所以表现为"偶发"。

### 修复

- `e2e/visual/onboarding.spec.ts`：展开图标点击改为 `click({ scroll: 'none' })`。图标本来就在视口内（`scroll: 'none'` 会在元素不可见时直接失败），这样截到的是真实用户点击后 `scrollLeft = 0` 的画面；`toHaveScreenshot` 断言、`maxDiffPixelRatio` 与用例全部保留。
- 三个宿主的密度 CSS 仍按原方案加兜底（`frontend/apps/tenant-app/src/App.vue`、`frontend/apps/platform-console/src/App.vue`、`frontend/packages/sub-app-kit/src/DevHost.vue`），值与 `theme.ts` 完全一致（14px / 8px），零像素变化，作为"首屏测量不依赖运行时注入"的防御；它不参与本次 flake 的修复。stylelint 的字面量规则不允许 `var()` 兜底里的长度，逐条用 `stylelint-disable-next-line declaration-property-value-disallowed-list -- 理由` 豁免，没有修改随 kit 发布的共享规则。
- 同类排查：除这三处全局 `.el-table` 密度声明外，其余 `--forge-*` 的 `font-size`/`font-family`/`padding`/`margin`/`height`/`width`/`gap` 都写在页面 scoped 样式或 `h()` 内联样式里，随字体/token 变化由浏览器重新布局，没有"测量后写死"的缓存，不需要兜底。`EmptyState` 三处 `--forge-font-family` 也不参与 el-table 列宽测量（空状态渲染在跨列的空块里），且字体栈与继承的 Element Plus 栈等价，故不加。

### 验证

- 改前：冷构建后第 1 次全量 215 passed；第 2 次 `laptop › onboarding mixed` 失败，**12405 px（ratio 0.02）**，稳定复现同样的整体左移 13px。
- 改后：连续 3 次全量视觉测试均 **215 passed**，未执行 `--update-snapshots`，基线零变化。
- `lint:ui`、`lint:style`、`pnpm -r typecheck`、`format:check`、`pnpm -r --workspace-concurrency=1 test` 全通过。
- 注：`## 验收标准` 里的 209 是开单时的用例数，现在全量为 215。

**2026-11-14 补充：兜底方案已撤回**

根因确定后，按主代理要求加的"构建期兜底"（`var(--forge-font-size-sm, 14px)`）经 A/B 验证对 flake **无影响**——加前加后失败像素数同为 12405。保留它需要 6 处 `stylelint-disable` 压制 token 规则，且注释里声称的机制（`el-table` 首屏按文字宽度测量并写死列宽）已被同一实验推翻。留着会误导后人、并削弱工单 05 刚建立的 token 唯一来源约束，故整体撤回（提交 `513b5158`）。

**最终修复只有一处**：`onboarding.spec.ts` 的展开图标点击改为 `click({ scroll: 'none' })`。展开图标本就在视口内，Playwright 默认的自动滚动会把横向溢出的 `el-scrollbar__wrap` 的 `scrollLeft` 从 0 推成 13，截图因此整表左移 13px；真实用户点击不会触发这个滚动。断言、`maxDiffPixelRatio`、用例全部保留，没有放宽或跳过。
