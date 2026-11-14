# 03: 主题 token 扩充与决策记录

**What to build:** 给主题 token 补齐缺的档位，并留下这次「只加不改、不新增语义色」的决策记录。见[规格 4.4、4.7](../spec.md#44-主题-token)。

**Blocked by:** 无（只加 token 与文档，不改变现有像素）

**Status:** ready-for-agent

- [ ] 新增 z-index 五档（下拉 / 吸顶 / 覆盖层 / 弹窗 / 提示），按层叠顺序命名，不写具体大数值以外语义
- [ ] 新增第二档阴影，与现有卡片阴影区分「静态卡片」与「浮起元素」
- [ ] 间距序列补 32 与 48 两档，用于区块之间
- [ ] 新增字体族一项，取值显式采纳 Element Plus 的系统字体栈；不引入 webfont
- [ ] **现有 15 个 token 的名称与取值一律不动**，不设删除期；在 ADR 里写明理由（仓库外已生成的 Sub-App 工程无法追踪）
- [ ] **不新增 `--forge-color-*` 语义色**：语义色一律沿用 Element Plus 的 `--el-color-*` 与 `--el-text-color-*`
- [ ] 新增 ADR：主题 token 是三层契约（primitive / semantic / component），并承担微前端样式隔离边界；说明为什么不删除旧名、为什么语义色走 Element Plus
- [ ] `GLOSSARY.md` 增两条术语 `Theme Token（主题 token）` 与 `Visual Baseline（视觉基线）`，各带同义禁止项，不写实现细节
- [ ] `frontend-design.md` 增 token 分层结构说明（哪层归谁、加档位的判断标准）
- [ ] 测试：主题测试覆盖新增 token 被写入根节点；视觉基线应无变化（若变化说明误改了取值）
- [ ] `pnpm lint:style`、`pnpm -r test`、`pnpm typecheck`、`pnpm format:check` 通过
