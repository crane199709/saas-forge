# 0.2.0 升级实现双轴审查与整改

日期：2026-11-17。规格：[spec.md](spec.md)。审查的固定仓库、基线、tip、diff 命令与提交清单保存在 `/private/tmp/v020-upgrade/review-targets.json`。审查之后平台已基于最新集成 `01205a3f6fd69e7dc433a3fc05b5b10457679544` 建立独立整改分支；没有改动 Lowcode。

## Standards

原独立审查报告：`/private/tmp/v020-upgrade/review-standards.md`。原结果为 **1 项明确规范问题（P3）**、Fowler 判断性 smell **0 项**；原报告保留，不把整改后的状态回写为原审查 0 项。

问题是新增 Java 类型引用没有遵循平台 `docs/standards/business-service.md` 和 AI 同路径副本「类型引用」的显式 import 规则；不存在同名冲突或名称遮蔽。已对报告所列位置最小修正：

- 平台 service、storage、IO 三份升级验收 fixture：导入 DynamicPropertyRegistry、Supplier，使用简单名。
- storage 验收 fixture：导入 OffsetDateTime、StandardCharsets，使用简单名。
- `storage_upgrade.py`：实际生成 Java 时加入 Set、Duration 的 import，并以简单名声明 StoragePurpose。
- AI FullStackIT：只修新增两处 TimeUnit 引用；原有两处全限定名保持原样。AI 提交 `5d2dc9258ba9f88b14ba23acb286062103fba1c2`，分支 `codex/v020-upgrade-ai-review-fix`，交专职 merger 合回最终试点分支。

本轮以已确认的规范失败作为 red，修复后在新的仓库外副本编译。纯类型引用规范整改不改变公开接缝，没有新增镜像实现的测试。

| 直接验证工程 | Spotless / Checkstyle / test-compile | 重新编译的 main / test Java 文件 |
| --- | --- | --- |
| 默认 Business Service | 全部通过，Checkstyle 0 违规 | 16 / 11 |
| storage | 全部通过，Checkstyle 0 违规 | 21 / 6 |
| 历史 IO | 全部通过，Checkstyle 0 违规 | 30 / 5 |
| AI Service | 全部通过，Checkstyle 0 违规 | 148 / 14 |

验证使用 JDK 17 与原候选 settings/m2，Maven 离线执行 `spotless:apply test-compile spotless:check`，不运行 Docker、不执行平台源码 install。storage 从当前辅助脚本的实际 AST 片段生成 DocumentService 后参与编译。三份 fixture 和 AI 修改文件与格式化、编译的副本逐字节一致；受影响测试及 storage 生成服务共 12 个 class 的 `javap -c -p` 输出与原验收一致。另通过 Python 编译与 `git diff --check`。日志、命令、摘要和来源影响判断在 `/private/tmp/v020-upgrade/review-fix-checks/`、`review-fix-receipt.json` 与 `review-fix-receipt.md`。

## Spec

原独立审查报告：`/private/tmp/v020-upgrade/review-spec.md`。原结果为 **0 项**：缺失或部分实现 0 项、未要求行为 0 项、实现错误 0 项。本轮未引入功能、配置、迁移或范围变更；仅对 Standards 已确认问题整改，没有重新运行完整双轴审查。

## 验收来源与边界

原最终候选仍是 `9f61c8a0073c82a900abeff45ce21c5600b6afb3` / `0.2.0`，清单 SHA-256 `ceed5ff8789ffd51f5727e3861c63f2ae90c4750c7cbc387a991b979d98adc94`。平台整改只影响未随包分发的 fixture/生成辅助脚本；AI 整改只影响测试源码。候选没有改变，无需重建或将已绿的通用四场景、Lowcode 185 项、AI 全栈等重型套件重复执行。

AI 原完整测试源码为 `17a934e8ad8a9ad6c727f2a234ddd35466de0d33`，原文档收据提交为 `e4b16654887e77af6334ca870cb9596c2bf3a634`；最新 `5d2dc925` 只执行本轮格式/编译验证，不能把原完整计数记为该提交上重新执行。原 readiness、通用及两份试点 receipt 保留；6 个封存文件与 124 个 Forge `_remote.repositories` 摘要均核对未变，不改 `forge-candidate` origin。

工单 01—08 继续 resolved，09 与父规格继续 needs-info，等待外部发布前置。没有 push/publish、创建或移动 tag、切换环境或归档 worktree。发布后目标仓库只读消费仍未执行，其余限制沿用 [08 验收](acceptance-08.md) 与独立试点原收据。
