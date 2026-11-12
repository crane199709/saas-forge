# 03: CI 校验、动作词白名单与文档

**What to build:** 按 [spec](../spec.md) 的“实现决策”，CI 重新生成目录并做字节比较，动作词白名单校验新权限码，文档说明消费方式。

**Blocked by:** 02

**Status:** resolved

- [x] CI 生成到临时位置并与入库文件逐字节比较，不一致时失败
- [x] 动作词白名单校验权限码动作段；存量例外写入显式清单
- [x] 新增违规只允许通过更新白名单与说明引入
- [x] 文档说明生成命令、目录用途、外部 Sub-App 如何消费
- [x] 测试覆盖：漂移失败、动作词违规、例外清单、生成一致
- [x] 运行时权威仍是 IAM 权限记录，不新增目录 API

## Comments

- 2026-11-12：工单 03 已领取，在 `codex/permission-catalog-03` 完成 CLI 字节校验、动作策略、Git 基线 CI 与消费文档；沿用已批准的生成命令与目录文件测试接缝。

## Answer

- 根命令新增 `--check`：先读取入库文件原始 bytes，真实生成到临时目录并逐字节比较，成功/失败均不修改入库文件；缺失、CRLF 与空白漂移会失败，提示在根目录运行 `python3 scripts/permissions/catalog.py`。默认生成命令及 `--offline` 保持可用，`--policy PATH` 可显式指定动作策略。
- `docs/contracts/permission-policy.json` 显式批准八个当前动作，每项附非空理由；真实现存权限均合规，`historicalExceptions` 为空。动作取最后冒号之后的完整段，要求两侧非空并精确匹配，裸 `read`、`order:read-extra`、`order:read:invented`、`order:Read` 均失败。
- 历史例外按完整 Permission Code 精确匹配、须附非空理由，且编码必须已存在于 `--previous`；校验覆盖 active/inactive。新增违规只能增加白名单动作并说明理由，不能在同一变更自称历史例外。
- `ci_previous.py` 从 Git 基线提取历史：PR 使用 base SHA、分支 push 使用 before、标签与其他事件使用 checkout 提交第一父提交。只有首次提交/首次 push 或已验证可达的基线中尚无目录才使用空历史；Git 错误明确失败。CI 传入该历史，防止删除 inactive 行或把新码写入 head 快照后冒充历史。
- 原 `backend-quality.yml` 无条件运行目录 Python 测试、Git 基线提取、真实 `--check`，Java setup 同样无条件；既有 Docker/Maven 全量/SDK gate 的 scope 保持原样，发布继续复用同一 Backend quality 工作流，无需修改 selector。
- 顺带修复工单 02 的旧 class 风险：默认导出使用 `clean install -DskipTests`，删除注解类后 collector 不再扫到遗留 class；显式 `--skip-export` 仍可复用已知产物。
- 消费文档 `docs/permission-catalog.md` 说明根命令、离线/检查参数、schema、active/inactive 历史、首次不回填运行库历史、仓内四服务覆盖边界、前端与外部 Sub-App 消费。`docs/platform-permission.md` 提供入口，GLOSSARY 仅修正目录来源。运行时权威仍为 Resource Manifest 与 IAM 的权限记录，不新增 API 或业务/前端编码规范。
- TDD 沿用已批准的 CLI 与目录文件接缝，新增行为均逐片 red → green。`python3 -m unittest discover -s scripts/permissions -p 'test_*.py' -v`：19 项通过，包含原生成测试和新字节比较与损坏目录诊断、动作/例外、真实临时 Git 仓基线、防历史删除/伪装、旧 class 清理测试。
- 真实 `python3 scripts/permissions/catalog.py --offline` 成功；随后 `python3 scripts/permissions/ci_previous.py --output /tmp/permission-catalog-03-previous.json` 与 `python3 scripts/permissions/catalog.py --offline --check --previous /tmp/permission-catalog-03-previous.json` 成功。两次均 clean install 本次源码、5 个 Java 导出测试通过，28 个 active 目录与已入库 bytes 一致；日志 `/tmp/permission-catalog-03-generate.log`、`/tmp/permission-catalog-03-check.log`。YAML 解析及 Java/目录步骤无条件接线检查通过，`git diff --check` 通过。
- 已合入集成 tip `152a311`。按主流程要求不重复工单 01/02 已完成的后端模块全量；GitHub 托管 CI 尚未触发，本地执行了其核心目录命令。
