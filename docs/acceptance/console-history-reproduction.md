# Console 历史与制品追溯

旧 Console 的固定源提交为 `b29a86390916619d79b709752467e3b3d691390f`。不依赖某台机器的 backup，也不向新前端 main 强推。以下命令只在两个全新临时目录中重建历史，Git 原始仓库保持不变：

```sh
# 从后端仓库执行；SF_HISTORY_ROOT 必须是已创建的空临时目录的绝对路径。
git clone --shared --no-checkout . "$SF_HISTORY_ROOT/source"
git -C "$SF_HISTORY_ROOT/source" checkout --detach b29a86390916619d79b709752467e3b3d691390f
git -C "$SF_HISTORY_ROOT/source" fast-export HEAD -- consoles/ > "$SF_HISTORY_ROOT/console-history.export"
git init "$SF_HISTORY_ROOT/import"
git -C "$SF_HISTORY_ROOT/import" fast-import < "$SF_HISTORY_ROOT/console-history.export"
git -C "$SF_HISTORY_ROOT/import" branch legacy-console-history HEAD
git -C "$SF_HISTORY_ROOT/import" bundle create "$SF_HISTORY_ROOT/legacy-console-history.bundle" legacy-console-history
git -C "$SF_HISTORY_ROOT/import" bundle verify "$SF_HISTORY_ROOT/legacy-console-history.bundle"
git -C "$SF_HISTORY_ROOT/import" ls-tree legacy-console-history consoles
```

要在新前端保留可查阅的历史引用，仅执行：

```sh
git fetch "$SF_HISTORY_ROOT/legacy-console-history.bundle" \
  refs/heads/legacy-console-history:refs/remotes/legacy/console-history
```

该命令不切换、合并或覆盖 main。后续移植选定业务时，以 `git format-patch` / `git am` 或明确来源的逐提交适配保存作者、日期、消息和修改轨迹；不要合并整套旧双 Console，也不要只复制末端快照替代历史。原始完整后端提交仍在原仓库；过滤掉无关后端改动后的提交 ID 会变化，不能将过滤后的 SHA 当作原始 SHA。

2026-09-28 实测：导出/导入保留 **113 个相关提交**；末端引用 `67b72dd36048bc93cd26b95f5b4eab3c2664b82f`；`consoles` 目录树 `61e854a3135598ba517eefed851b334e75f27e54` 与原提交逐字节一致，bundle verify 通过。新前端 main 没有被改写。某些 Git 版本对已不存在的路径做 fast-export 会报 ambiguous argument，因此必须在上述旧提交的临时 checkout 中执行，不在已删除 consoles 的当前目录执行。

## 发布制品

`dist/`、依赖与本机备份不是版本化发布制品。旧截图/静态 Remote 中已跟踪的文件包含在上述 Git 树；未跟踪构建目录只能作为本机恢复材料，不冒充已发布版本。原始提交、锁文件和当时工具链用于复现旧构建；已有 CI 附件若已过保留期，必须注明制品不可取得，不能重新生成后声称是原发布字节。

新前端保留固定 Soybean 上游 `7613bd206cd42001b40e3eafceeb895dcbc277a8` 的祖先关系与许可证。新的静态制品在前端独立构建，记录前端 Git SHA/dirty、lock 摘要和 Client 来源；后端 Java/Client 发布仍由自己的 workflow 维护。精确 Client 版本由前端 manifest + lock 完整性固定，先发布兼容后端与 Client，再显式升级前端；兼容后端升级不要求重新发布前端。破坏性契约必须显式版本化。

当前本轮安装的是 npm `@crane199709/saas-forge-api-client@0.4.0`，包内 `contract-source.json` 来源为 `ebff4b338d0c4b39e51488ac1e9b98885318d438`、`dirty=false`。前端冒烟入口会比对 manifest、lock 与实际安装包，记录来源，不调用后端生成器。本票没有发布新 npm/Java/前端制品；历史发布记录见 `issue-203-versioned-client.md`，当前组合与真实运行的证明范围见 #206 验收记录。
