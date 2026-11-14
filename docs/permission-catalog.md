# Permission Catalog（权限目录）

[权限目录快照](contracts/permission-catalog.json) 汇集仓内服务与核心组件声明的 Permission Code，供代码审查、前端和外部 Sub-App 在开发期消费。运行时授权仍以 Resource Manifest 和 IAM 的权限记录为准；本功能不新增目录 API，也不从目录写回授权数据。

## 生成与检查

在仓库根目录运行，要求 Python 3、JDK 17 和项目 Maven Wrapper 可用；不需要启动应用、数据库或其他中间件：

```sh
python3 scripts/permissions/catalog.py
python3 scripts/permissions/catalog.py --check
```

默认命令先对 IAM、Platform、Demo 及其依赖执行 `clean install -DskipTests`，清理已删除声明遗留的 class，安装本次 SDK，再通过 `*ManifestExportTest` 导出真实 Resource Manifest。目录目前覆盖 `forge-iam`、`forge-iam-platform`、`forge-platform`、`demo-service`，期待服务的产物缺失会失败。新增仓内服务时须把其导出入口与预期服务加入根脚本。

依赖已在本机 Maven 缓存时可加 `--offline`；它只控制 Maven 离线，不跳过构建或导出：

```sh
python3 scripts/permissions/catalog.py --offline
python3 scripts/permissions/catalog.py --offline --check
python3 -m unittest discover -s scripts/permissions -p 'test_*.py' -v
```

`--check` 先读取入库文件的原始字节，再生成到临时目录比较，成功或失败均不改入库文件。文件缺失、内容变化、CRLF 或空白漂移都会失败，并提示根目录修复命令 `python3 scripts/permissions/catalog.py`。修复后检查并提交目录差异。

`--skip-export` 可显式复用当前四份 `target/permission-manifests/<service>/resource-manifest.json`；仅在确认它们来自本次源码时使用。CI 始终走真实构建导出，不使用该参数。

## 产物格式与历史

目录为 UTF-8、LF、末尾换行的 JSON，按 `code` 排序，不含时间、构建路径或运行期数据：

```json
{
  "schemaVersion": 1,
  "permissions": [
    {
      "service": "demo-service",
      "code": "order:read",
      "name": "查看订单",
      "group": "订单",
      "realm": "tenant",
      "status": "active"
    }
  ]
}
```

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 当前为 `1` |
| `permissions` | Permission Code 条目数组 |
| `service` | 声明该码的服务 |
| `code` | 全局唯一 Permission Code |
| `name` / `group` | 显示名与分组；分组可为 `null` 或空串 |
| `realm` | `tenant` 或 `platform` |
| `status` | 当前声明为 `active`；历史存在、当前已移除为 `inactive` |

默认以仓内上一版目录作为历史。当前声明移除后保留原码、名称、分组、服务与 Realm，并标记 `inactive`；同一服务与 Realm 再次声明时恢复 `active`，名称与分组取当前声明。已入库编码不能转交其他服务或 Realm。完全一致的重复项会去重，归属或展示信息冲突会失败。

首次基线只收录当前源码声明，不回填运行库中尚未入库的历史权限。目录中的 `inactive` 只是声明历史，不表示已替代 IAM 的权限停用流程。

Backend quality 工作流无条件运行目录测试与真实 `--check`，因此仅修改目录或策略也会检查；发布复用同一工作流。CI 通过 `ci_previous.py` 提取已接受的 Git 历史：PR 取 base SHA，main push 取 `before`，手动、可复用及其他调用取当前提交的第一父提交。无父提交、首次 push 或基线提交尚无目录时使用空历史；Git 提交不可达或命令错误会失败。CI 使用该基线作为 `--previous`，避免在当前提交删掉 inactive 行后自洽通过。它不保存尚未合并的 PR 内“新增后又删除”的中间声明，也不接受手工追加且当前源码未声明的 inactive 行作为历史导入。

## 动作词策略

[动作策略](contracts/permission-policy.json) 包含两个映射，每个值都必须是非空理由：

- `actions`：动作词到批准理由；当前为 `create`、`delete`、`export`、`import`、`importRollback`、`manage`、`publish`、`read`、`reset`、`rollback`、`update`、`use`、`viewRaw`。
- `historicalExceptions`：完整 Permission Code 到历史保留理由；当前没有需要例外的存量编码，因此为空。

动作取最后一个冒号之后的完整段，要求冒号两侧非空，并精确匹配白名单。例如 `iam:role:manage` 与 `order:read` 通过，裸 `read`、`order:read:invented`、`order:read-extra` 和 `order:Read` 不通过。该策略也校验 inactive 条目，不改变 SDK 的运行时编码语法。

确需新动作时，在 `actions` 登记动作及其用途理由。历史例外仅按完整码匹配，且该码必须已经存在于 `--previous` 基线；不支持通配符、资源前缀或服务整体放行。新增违规码不能通过同时提交一个“历史例外”引入，CI 的 Git 基线会拒绝这种情况。

AI 服务用 `use` 区分实际使用助手/工具与读取历史，用 `publish`、`rollback` 区分配置版本操作与草稿修改。例如 `ai:conversation:use`、`ai:prompt:publish`、`ai:prompt:rollback`。Manifest 的声明、菜单引用与角色引用由 SDK/IAM 校验；动作白名单由目录命令对导出的 Manifest 校验，外部 AI 服务应把该命令加入自己的 CI。运行时编码语法保持兼容，不为旧服务引入额外的动作词拒绝。AI 权限由外部 AI 服务声明，平台仓内目录不预占这些编码。

## 前端与外部 Sub-App 消费

前端可从所依赖版本的目录生成按钮、路由权限常量。生成时选择 `status == "active"` 的条目，并按服务或 Realm 过滤；运行时仍用 IAM 返回的实际权限判断可见性，不能把目录中的所有 active 码视为当前用户已获授权。

外部 Sub-App 可把发布版本中的目录文件固定到自己的构建输入，检查自己的权限码是否与任意现有条目冲突；冲突检查应包含 inactive 码，避免复用历史编码。仓内目录不收录仓库外服务，也不能保证覆盖尚未上报的外部服务；运行时全局唯一性仍由 IAM 在 Resource Manifest 上报时校验。

如需在开发期合并已有 Resource Manifest，可显式指定输入、预期服务、历史与输出，复用同一校验：

```sh
python3 scripts/permissions/catalog.py \
  --manifest /tmp/orders/resource-manifest.json \
  --expected-service orders \
  --previous docs/contracts/permission-catalog.json \
  --output /tmp/orders-catalog.json
```

`--manifest` 和 `--expected-service` 均可重复，显式输入会跳过仓内构建。`--output` 只改变输出位置；`--previous` 默认仍为仓内目录，显式指定但不存在会失败。调用方需要空历史时应提供 `{"schemaVersion": 1, "permissions": []}` 文件；已有历史与动作策略仍会校验。
