# 领域文档

工程 skills 在探索代码库前，应按以下规则读取领域文档。

## 探索前读取

- 根目录的 `CONTEXT-MAP.md`，从中找到与当前主题相关的各个 `CONTEXT.md` 并读取。
- `docs/adr/` 中与当前工作区域相关的系统级 ADR。
- 从 `CONTEXT-MAP.md` 取得 context root，再读取该 root 下 `docs/adr/` 中与当前上下文相关的 ADR。

若这些文件不存在，静默继续，不要主动提示或预先创建。`/domain-modeling` 会在术语或决策实际明确后再创建它们。

## 文件布局

本仓库采用 multi-context（多上下文）布局，保留既有命名：`CONTEXT-MAP.md` 承担技能模板中 `GLOSSARY-MAP.md` 的上下文索引职责，各 `CONTEXT.md` 承担 `GLOSSARY.md` 的领域术语职责。

上下文清单和路径以根目录 `CONTEXT-MAP.md` 为准。系统级 ADR 位于 `docs/adr/`，上下文级 ADR 位于各 context root 下的 `docs/adr/`（若存在）。

## 使用统一术语

Issue 标题、重构建议、假设和测试名称中的领域概念，应使用相关 `CONTEXT.md` 定义的术语。若没有对应术语，应重新确认是否引入了不必要的同义词，或将该缺口记录给 `/domain-modeling`。

## 标记 ADR 冲突

若输出内容与已有 ADR 冲突，必须显式说明冲突，而不是静默覆盖该决策。
