# 05: 独立生成 Sub-App 时提示 monorepo 命令

**Status:** resolved

**现象：** 发布模式（非 `--workspace`）生成后，`bin/create.ts` 末尾仍提示 `pnpm --filter @forge/<code>-sub-app dev` / `build`；独立工程没有 pnpm workspace，`--filter` 命令不适用。

**复现：** `pnpm --package=@crane199709/sub-app-kit@<版本> dlx forge-create-sub-app lowcode 5176`，看最后几行输出。

**期望：** 发布模式提示 `pnpm dev`、`pnpm build`；仅 `--workspace` 模式保留 `--filter` 写法。`create.test.ts` 覆盖两种模式的提示。

## Comments

修复（2026-11-11）：发布模式提示 `pnpm dev` / `pnpm build`，`--workspace` 模式保留 `pnpm --filter`。`create.test.ts` 两种模式各加断言，kit 测试 6 个通过。
