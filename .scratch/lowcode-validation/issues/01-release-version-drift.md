# 01: 发布后源码版本未递增，npm 同版本与源码不一致

**Status:** needs-triage

**现象：** `v0.1.0` 已发布（npm `@crane199709/sub-app-kit@0.1.0` 修改时间 2026-10-31），之后脚手架规范等改动仍以 0.1.0 留在源码中。按 `docs/sub-app.md` 用发布包生成的工程会依赖 `@crane199709/sub-app-kit/stylelint`、`lint:style` 等发布包里没有的内容；而 `forge-service-archetype` 在本地 `~/.m2` 的 0.1.0 又可能是任一时刻的源码构建。同一版本号对应多份内容，业务方无法判断自己用的是哪份。

**复现：** `npm view @crane199709/sub-app-kit time.modified` 与 `git log -1 -- frontend/packages/sub-app-kit` 对比；`git tag` 只有 `v0.1.0`，各 `package.json` 与 POM 版本仍为 0.1.0。

**业务工程绕行：** `lowcode-sub-app/pnpm-workspace.yaml` 用 `overrides` 指向由当前源码 `pnpm pack` 的同版本 tarball（`.forge-packages/`）。

**期望：** 发布后源码版本立即进入下一个开发版本（如 0.2.0-SNAPSHOT / 0.2.0-dev），同一版本号只对应一份发布内容；`scripts/release/version.py` 或 CI 可检查这一点。另需发布包含脚手架规范的新版本。
