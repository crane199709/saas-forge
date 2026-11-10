# 04: 模板冒烟与 CHANGELOG

**What to build:** 模板冒烟覆盖新增的前端样式检查，CHANGELOG 记录脚手架带入规范与已生成工程的升级方法。见规格第 2、5 节。

**Blocked by:** 02, 03

**Status:** ready-for-agent

- [ ] `deploy/template-smoke/consume.sh` 的 Sub-App 步骤增加 `pnpm lint:style`；视觉测试不进冒烟
- [ ] `CHANGELOG.md` 写明：生成工程新增 `AGENTS.md` 与 `docs/standards/`、archetype 的 schema 检查、Sub-App 的 `lint:style` 与视觉测试；已生成工程如何从新版本复制文档与配置
- [ ] `docs/business-service-guide.md`、`docs/sub-app.md` 提及生成工程自带的规范文档
- [ ] 本地发布流程跑一次模板冒烟并记录结果；无法运行时说明原因
