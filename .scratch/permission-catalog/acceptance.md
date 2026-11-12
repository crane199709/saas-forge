# 权限目录验收记录

日期：2026-11-12

集成分支：`codex/permission-catalog`

规格：[spec.md](spec.md)

## 实现结果

- [01：核心服务导出](issues/01-core-manifest-export.md)、[02：目录生成](issues/02-catalog-generator.md)、[03：CI 与动作词](issues/03-ci-check-and-verbs.md) 均为 `resolved`，验收项全部完成。
- 入库目录为 `docs/contracts/permission-catalog.json`，包含 IAM、IAM Platform Realm、Platform 与 Demo 四份清单的 28 个权限码；当前均为 active，后续移除的权限保留为 inactive。
- 根命令从当前源码执行清理构建、Manifest 导出和目录生成，不依赖运行中的数据库。冲突、缺失产物、非法编码和动作词违规均导致命令失败。
- CI 无条件运行目录命令测试、Git 基线提取和字节比较；历史例外必须存在于基线，新动作必须填写批准理由。
- 使用方式与消费边界见 [目录文档](../../docs/permission-catalog.md)。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| IAM 全模块测试 | 316 项通过，失败、错误、跳过均为 0 |
| Platform 全模块测试 | 63 项通过，失败、错误、跳过均为 0 |
| Demo 全模块测试 | 106 项通过，失败、错误、跳过均为 0 |
| Python 命令行为测试 | 19 项通过，覆盖生成、去重、冲突、历史状态、漂移、动作词、Git 基线和清理旧类文件 |
| Manifest 导出 | 5 项通过；重复导出字节一致 |
| 真实目录生成 | 连续生成字节一致，UTF-8、LF、末尾换行、按 code 排序且无重复 |
| 合并后主工作区验收 | 真实清理构建及 Git 基线下的 `--offline --check` 成功，入库文件无变化 |
| 格式与差异 | Spotless、YAML 解析及 `git diff --check` 通过 |

合并后验收命令（仓库根目录）：

```sh
env GITHUB_EVENT_NAME=workflow_dispatch python3 scripts/permissions/ci_previous.py \
  --output /tmp/permission-catalog-final-previous.json
python3 scripts/permissions/catalog.py --offline --check \
  --previous /tmp/permission-catalog-final-previous.json
```

本次完整模块测试共 485 项。日志：`/tmp/permission-catalog-01-full-tests.log`、`/tmp/permission-catalog-02-demo-full-tests.log`；合并后验收日志：`/tmp/permission-catalog-final-check.log`。

## Standards

以 `f8967446009e3163f57d0cc0ea562483ca7c142e` 为基线，对实现提交至 `eb1b884406cc47b10aa94e22e00587fd0ce143f8` 独立审查：0 项问题。未发现有具体证据的规范违规或需要修改的代码异味。

## Spec

同范围独立核对规格与工单 01–03：0 项问题。Manifest 来源、确定性、冲突与历史处理、动作策略、CI 字节比较和消费文档符合规格。

双轴审查：Standards 0 项，Spec 0 项；两轴均无最严重问题。

## 验证边界与工作区

- 本地提交未推送，未创建 PR，GitHub 托管 CI 尚未运行；本地已验证实际构建、导出、Git 基线与字节检查命令。
- 初次快照覆盖当前源码声明，不回填运行库中从未入库的历史权限；运行时授权仍以 Resource Manifest 和 IAM 权限记录为准。
- 工单 01、02 的临时 worktree 已归档。工单 03 的 worktree 被应用标为本聊天的主检出，归档工具拒绝删除；保留该干净检出并同步最终集成状态。
