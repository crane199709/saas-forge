# 03: 主题 token 扩充与决策记录

**What to build:** 给主题 token 补齐缺的档位，并留下这次「只加不改、不新增语义色」的决策记录。见[规格 4.4、4.7](../spec.md#44-主题-token)。

**Blocked by:** 无（只加 token 与文档，不改变现有像素）

**Status:** resolved

- [x] 新增 z-index 五档（下拉 / 吸顶 / 覆盖层 / 弹窗 / 提示），按层叠顺序命名，不写具体大数值以外语义
- [x] 新增第二档阴影，与现有卡片阴影区分「静态卡片」与「浮起元素」
- [x] 间距序列补 32 与 48 两档，用于区块之间
- [x] 新增字体族一项，取值显式采纳 Element Plus 的系统字体栈；不引入 webfont
- [x] **现有 15 个 token 的名称与取值一律不动**，不设删除期；在 ADR 里写明理由（仓库外已生成的 Sub-App 工程无法追踪）
- [x] **不新增 `--forge-color-*` 语义色**：语义色一律沿用 Element Plus 的 `--el-color-*` 与 `--el-text-color-*`
- [x] 新增 ADR：主题 token 是三层契约（primitive / semantic / component），并承担微前端样式隔离边界；说明为什么不删除旧名、为什么语义色走 Element Plus
- [x] `GLOSSARY.md` 增两条术语 `Theme Token（主题 token）` 与 `Visual Baseline（视觉基线）`，各带同义禁止项，不写实现细节
- [x] `frontend-design.md` 增 token 分层结构说明（哪层归谁、加档位的判断标准）
- [x] 测试：主题测试覆盖新增 token 被写入根节点；视觉基线应无变化（若变化说明误改了取值）
- [x] `pnpm lint:style`、`pnpm -r test`、`pnpm typecheck`、`pnpm format:check` 通过

## Comments

**2026-11-14 合并与验收**

提交：`ea0475eb` 主题 token 与测试、`e9808fdf` ADR-0029 与文档、`5132dc45` 合并到集成分支。

验证：`lint:style`、`typecheck`、`format:check`、`--frozen-lockfile` 通过；集成分支上串行全量测试通过。新增 token 无组件引用，`git diff` 为纯新增，视觉像素不变。

发现：跑本工单的视觉全量时 **4 张字典页基线（desktop/laptop）已经过期**——它们最后生成于 `a9e94377`（2026-11-10），而字典页签与工作台改名在 2026-11-13 才合入。关掉本工单改动后仍同样失败，证明与本工单无关。这 4 张随后被工单 01 的基线重跑一并刷新，问题已消除。

注：`pnpm -r test` 并行跑会在本机出现 platform-console 5000ms 超时假阳性；串行（`--workspace-concurrency=1`）全部通过。集成分支验收一律用串行。
