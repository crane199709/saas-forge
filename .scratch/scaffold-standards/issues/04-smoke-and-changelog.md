# 04: 模板冒烟与 CHANGELOG

**What to build:** 模板冒烟覆盖新增的前端样式检查，CHANGELOG 记录脚手架带入规范与已生成工程的升级方法。见规格第 2、5 节。

**Blocked by:** 02, 03

**Status:** ready-for-agent

- [x] `deploy/template-smoke/consume.sh` 的 Sub-App 步骤增加 `pnpm lint:style`；视觉测试不进冒烟
- [x] `CHANGELOG.md` 写明：生成工程新增 `AGENTS.md` 与 `docs/standards/`、archetype 的 schema 检查、Sub-App 的 `lint:style` 与视觉测试；已生成工程如何从新版本复制文档与配置
- [x] `docs/business-service-guide.md`、`docs/sub-app.md` 提及生成工程自带的规范文档
- [x] 本地发布流程跑一次模板冒烟并记录结果；无法运行时说明原因

## Comments

### 规范版本标注统一

两份规范统一为「monorepo 原文不含版本占位符，生成时在头部注入一行 HTML 注释」：`<!-- 平台版本 X：由 <生成器> 复制，不在本工程修改；平台升级时按 CHANGELOG 重新复制。 -->`。

- sub-app：`create.ts` 已是这种写法，未改。
- archetype：Maven 资源过滤只能替换文件中已有的占位符，不能在文件头部插入内容，因此去掉 `docs/standards/business-service.md` 中的 `> 适用平台版本：@project.version@`，构建时改为原样复制（`filtering=false`），由新增的 `META-INF/archetype-post-generate.groovy` 在生成时以 archetype 版本写入头部注释。这偏离规格第 2 节「版本号按 `@` 分隔符资源过滤写入」，原因同上；模板自带的 `AGENTS.md` 不在 monorepo 根目录、无单一来源问题，仍用资源过滤写版本。
- `verify.groovy` 改为断言：仓库原文不含 `@project`，生成副本首行是含平台版本的注释、其余与原文完全一致。先改断言跑红（`仓库规范原文不应包含版本占位符`），再实现转绿。

### 验证

- `./mvnw -o -pl forge-service-archetype clean verify -Parchetype-it`：通过，生成工程 17 项测试通过，`verify.groovy` 通过。
- `node --test bin/create.test.ts`（sub-app-kit）：2 项通过。

### 模板冒烟

`deploy/template-smoke/run.sh`（本地 Verdaccio + 临时 Maven 仓库发布 0.1.0）全程通过，退出码 0：

- 后端：仓库外 archetype 生成 `smoke-service`，`verify` 17 项测试通过（含 `SchemaCommentTest` 的 SDK 表检查）。
- 前端：从已发布 npm 包运行 `forge-create-sub-app` 生成 `smoke-sub-app`，`pnpm install`、`lint`、`lint:style`、`typecheck`、`build` 通过；视觉测试未进冒烟。
- 两个生成工程的规范文档首行均为 `<!-- 平台版本 0.1.0：由 <生成器> 复制… -->`，写法一致。
- 主流程 E2E `template-smoke.spec.ts`（Manifest、网关路由与成员权限）1 项通过，制品消费自检通过。
