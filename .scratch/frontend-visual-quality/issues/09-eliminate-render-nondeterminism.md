# 09: 消除视觉截图的两态渲染

**What to build:** 让同一份代码、同一台机器上的同一张截图稳定产出同一批像素，从而让视觉门禁可以收紧到能抓住局部漂移。

**Blocked by:** 无

**Status:** resolved

## 现象

同一份代码（`frontend-visual-quality` 分支），同一台 macOS，连续两次 `--update-snapshots=all`，被改写的基线文件集合**不同**：

| 轮次 | 被改写的文件 |
|---|---|
| 第一次 | `bulk-invitation-sending-laptop`、`import-confirmation-paging-first-laptop`、`import-confirmation-revalidation-desktop`、`onboarding-error-laptop`、`service-credentials-disable-laptop` |
| 第二次 | `import-confirmation-paging-first-laptop`、`import-confirmation-revalidation-desktop`、`menu-layout-rename-desktop`、`onboarding-error-laptop`、`platform-navigation-default-laptop`、`service-credentials-rotate-laptop` |

重叠的只有 3 个。229 张里 224 张每次都逐字节相同，不稳定的是一小部分。

## 关键线索

`service-credentials-disable-laptop` 的差异肉眼可见：同一段弹窗提示文字，一版笔画明显偏粗、一版正常，折行点也随之差一个字——这不像抗锯齿噪声，更像**用了不同的字体或字重回退**。差异区 2470 px、占 0.31%。

另一个已经查实的同族问题（工单 06）根因是 Playwright 的 `click()` 触发嵌套滚动容器横向滚动，与字体无关，已修。

## 待查

1. 抓一次两态样本：对 `service-credentials-disable-laptop` 连续重生成，直到拿到两个不同版本，把两张裁图留下。
2. 在两态下分别读取 `getComputedStyle` 的 `font-family` / `font-weight`，以及 `document.fonts` 的就绪状态与已加载字体列表——判断是否发生了回退字体。
3. 检查该页面文字是否使用了由运行时 token 提供的字体族（`--forge-font-family`），若是，首屏可能在 token 注入前用继承字体渲染。
4. 复现 `menu-layout-rename-desktop`、`platform-navigation-default-laptop`、`service-credentials-rotate-laptop` 这批第二轮才出现的文件，看是否同一机制。

## 与其他工单的关系

- 工单 08 已决定：**先消除这里的非确定性，再收紧 `maxDiffPixelRatio`**。现在收紧会让这批噪声变成假失败。
- 修好后应回头处理工单 08 剩下的目标：把阈值调到能抓住 0.1%–0.3% 量级的局部漂移，并全量重生成一次基线。

## 验收标准

- [x] 连续 5 次 `--update-snapshots=all`，被改写的文件集合稳定（理论上为 0；若仍有，必须能解释原因）。
- [x] 带同一套基线跑 `--repeat-each=3` 全绿。
- [x] 结论写进 `frontend-design.md` 的「视觉基线」一节。

## Comments

### 2026-11-16 根因更正与修复

本次使用 macOS arm64、仓库固定的 Playwright 1.64.1 / Chromium 与本地构建产物。Browser plugin not available，使用现有 Playwright HTTP mock 视觉接缝；诊断脚本、原图和日志位于 `/private/tmp/forge-visual-09/`。

1. **服务凭证：固定列点击导致背景滚动。** 默认点击的 40 次采样出现 6 次零容差失败；截图两态分别对应表格 `scrollLeft = 0 / 160`。弹窗的计算样式、位置与 CDP 实际字体（`PingFangSC-Regular`）一致，原先“字体回退或字重变化”的推断不成立。真实用例补上滚动断言后，修复前 20 次重复运行得到 1 次失败，明确报出 `Expected: 0 / Received: 160`。停用、轮换及发放后停用的固定列按钮改用 `click({ scroll: 'none' })`，保留滚动断言；单变量对照的 40 张截图逐字节一致。
2. **侧栏：截图混入菜单展开过渡。** 展开容器 `overflow: hidden` 时包含子项外边距，恢复后发生外边距折叠，导致后续菜单项相差 4px；与字体无关。新增 `expectExpandedMenusSettled`，调用方确认目标分组 `is-opened` 后等待 `max-height: none`，用于导入确认、订单与平台导航截图。对照试验中四个受影响场景各五次采样产出相同 PNG；旧基线中混有过渡状态，须统一重生成。
3. **公告：执行日期改变业务状态。** 两端公告夹具在 2026-11-15 到期，运行日期已超过该时间。固定为 2026-11-14 并断言“发布中”，保留原用例的业务状态覆盖。
4. **报告链接：指针落在新出现的按钮上。** 导入弹窗打开后，指针有时位于“下载错误报告”链接上。采样确认非悬停/悬停颜色分别为 `rgb(96, 98, 102)` / `rgb(144, 147, 153)`，按钮始终可用；差异为 817 像素，而默认单像素颜色容差仍会放过。固定悬停的复现使新增颜色断言稳定失败；截图前移开指针，并断言非悬停颜色。翻页后同样移开指针，避免把分页控件的悬停态写入基线。
5. **字典详情：背景表格保留行悬停。** 全量哈希对比发现详情抽屉后方表格行在白色与悬停底色之间变化，涉及 35918 像素、单通道最多相差 10。字典历史截图前同样移开指针，并断言 `.el-table__row.hover-row` 数量为零；详情用例定向重复 10 次通过。

视觉门禁由 `maxDiffPixelRatio: 0.005` 收紧为 `0.001`，以覆盖工单 08 记录的 0.13%—0.31% 局部漂移；小于整图 0.1% 的变化仍可能漏过，滚动及公告状态使用独立 DOM 断言。内部视觉基线规范同步记录根因、等待方式与复验方法。

### 最终五轮基线验收

三套应用使用相同源码构建产物，四个 worker 连续执行五轮 `--update-snapshots=all`，每轮保存 PNG 与 SHA-256 清单。当前为 221 个用例、235 张基线，数量与文件名集合均保持一致。

| 轮次 | 结果 | 相对上轮的 PNG 变化 |
| --- | --- | --- |
| 1 | 221 passed | 相对原基线：13 张状态修正、2 张边缘尾差 |
| 2 | 221 passed | 2 张边缘尾差 |
| 3 | 221 passed | 3 张边缘尾差 |
| 4 | 221 passed | 3 张边缘尾差 |
| 5 | 221 passed | 3 张边缘尾差 |

13 张状态修正后的图在后四轮均逐字节一致：字典历史详情/比较各两档共 4 张（清除行与按钮悬停）；导入确认 3 张（菜单终态、报告链接非悬停色）；平台导航及默认导航两档共 4 张（菜单终态）；订单页两档共 2 张（菜单终态）。已逐张查看前后对照，以及 1440/1024 的完整导入页面截图。

**并非所有 PNG 都逐字节相同。** 剩余变化仅位于圆角和阴影边缘，每张 1—14 个像素、单通道最大相差 1—3 个色阶，未出现控件状态或几何位移。这些绘制尾差与前述 817—36028 像素的悬停变化分开记录，不扩大容差；末轮仍偏离原图的 `onboarding-error-laptop`、`error-feedback-platform-create-error-desktop/laptop` 已从原基线还原，其余尾差文件末轮已回到原图。最终仅保留上述 13 张状态修正。

`pnpm test:visual:update` 已固定为 `--update-snapshots=all`，避免默认更新模式继续跳过被容差放过的变化。完整证据位于 `/private/tmp/forge-visual-09/complete/`：`regeneration-summary.json`、`manifest-1.json`—`manifest-5.json`、`pixel-census.json`、`baseline-resolution.json` 及每轮日志。

### 交付复验与关闭

- 正式入口 `pnpm test:visual --repeat-each=3 --workers=4 --output=/private/tmp/forge-visual-09/complete/repeat-results --reporter=list` 重新构建三套前端后 **663/663 通过**，零失败；运行前后 235 张基线 SHA-256 完全一致。日志 `complete/repeat.log`，清单核对 `complete/repeat-manifest-check.json`。
- 导入失败报告的真实页面与交互在 1440×900、1024×768 通过：URL/标题正确、页面非空、无 Vite 错误层、控制台 warning/error 与 pageerror 均为空。浏览器 Chromium 156.0.8078.4，页面为 `http://127.0.0.1:5184/apps/demo/overview`；证据 `qa/1440.json`、`qa/1024.json` 与 `evidence/import-fixed-*.png`。
- `pnpm typecheck:tools`、`pnpm format:check`、`pnpm lint:style`、`pnpm lint:ui` 通过，后者扫描 24 个文件、0 warning；`git diff --check` 通过。
- 验收范围为 macOS Chromium 与既有 HTTP mock 视觉接缝；本次改动集中在测试准备、断言、门禁和内部规范，未运行真实业务后端联调或其他系统/浏览器验收。工单三个验收项完成，状态关闭为 `resolved`。
