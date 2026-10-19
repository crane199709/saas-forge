# Console UI 与消费边界

统一 Console 在独立 [saas-forge-web](https://github.com/crane199709/saas-forge-web) 维护，复用完整 Soybean Admin Element Plus 底座（[ADR 0050](adr/0050-consoles-adopt-soybean-element-plus.md)、[ADR 0052](adr/0052-unified-console-authentication-uses-versioned-session-protocol.md)）。布局、品牌、键盘与无障碍等现行 UI 规则已迁入前端仓的 [Console UI 与语言规则](https://github.com/crane199709/saas-forge-web/blob/main/docs/console-ui-and-locale-rules.md)，本文不再重复。

与后端相关的边界仍以本仓为准：Cookie、Origin 与 Fetch Metadata 由浏览器管理，不作为 UI 业务参数（见 `CLAUDE.md`）；原操作恢复以服务端为权威（[ADR 0045](adr/0045-console-operation-recovery-is-server-authoritative.md)）。

检查归属见[前端检查与后端验证归属](console-testing-baseline.md)，未迁覆盖见 [#207 清单](acceptance/issue-207-cleanup.md)，旧实现按[历史追溯](acceptance/console-history-reproduction.md)复现。本文迁出前的完整内容可从 Git 历史查看。
