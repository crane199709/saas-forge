# 09: 消除视觉截图的两态渲染

**What to build:** 让同一份代码、同一台机器上的同一张截图稳定产出同一批像素，从而让视觉门禁可以收紧到能抓住局部漂移。

**Blocked by:** 无

**Status:** needs-triage

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

- 连续 5 次 `--update-snapshots=all`，被改写的文件集合稳定（理论上为 0；若仍有，必须能解释原因）。
- 带同一套基线跑 `--repeat-each=3` 全绿。
- 结论写进 `frontend-design.md` 的「视觉基线」一节。
