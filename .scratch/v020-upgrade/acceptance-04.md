# 工单 04：已发布 storage 增量升级与已有文件验收

日期：2026-11-17。工单：[04](issues/04-storage-upgrade.md)。本票在当前 `0.2.0-SNAPSHOT` 候选阶段验收通过；最终 `0.2.0` 坐标需由后续固定候选复用本入口复验。IO、试点及发布就绪仍按各自工单判定。

## 输入、候选和独立样例

- 实现起点：集成 `eed5d736`，分支 `codex/v020-upgrade-04`。复用 02 的真实发布输入下载/生成框架和 05 的同库、同 S3、跨进程 HTTP 验证模式，storage 文件、数据、迁移和结果均属于独立场景。
- 旧输入来自实际 Maven 发布仓库 `https://maven.pkg.github.com/crane199709/saas-forge` 的 `0.1.0`，不是源码重建或本机 install。29 个 POM/JAR 的原始来源、最终 origin、大小和 SHA-256 在 `/private/tmp/v020-upgrade/04-green/published/provenance.json`；复用 02 缓存前逐项核对来源和摘要。跨 origin 重定向不转发 Authorization，凭证不落盘。
- 发布 storage JAR：`20d2249773b0df3235e5952fd0f550a99f0861d840a266d07321cfbdc4cd6777`；其实际 `schema.sql`：`ef5f128e35a6de5856771e5682f33475cc4fd240216e721cd5d4ba76a0dca87b`，没有 purpose 和 retention 两列。该旧 schema 原字节成为样例 V8。
- 候选 `/private/tmp/v020-upgrade/candidate-54115992`，源码提交 `54115992e00691be57b912e46593709c77c344a1`，版本 `0.2.0-SNAPSHOT`；候选清单 SHA-256 `999ef03043e0926e1c5d894f975e6c3a979b2e4bf22d68d90c4301cbc709f79b`。候选 storage JAR：`bfaa86493fa693245ea64116b02bcf42da5d5eeeb9c61b71a58ad22bfee884e0`。
- 两个服务 JAR 内嵌的 storage 包均与各自实际输入 SHA-256 相同。旧服务 JAR：`6d27f519315e16b94180b0f38ec78820066bd853468df77aa760aab8a9391415`；候选服务 JAR：`5fa4b4f353d25bf52c4ffd4830b21f346def63cd37541cd15ea6ed3e49942d39`。verify 后制品摘要仍相同。
- 样例从实际发布 archetype 生成，业务坐标 `com.example:retained-service:7.4.0-SNAPSHOT`、包 `com.example.retained`、Manifest 版本 `11`。保留 02 的业务自定义项，另在旧工程引入 storage、实际旧 schema 与业务文档/文件引用功能；不把 storage 加入默认脚手架。
- 独立 Maven 缓存只复制第三方依赖，排除本机全部 Forge 制品。旧 SDK 从发布输入 file 仓库解析，目标 SDK 从固定候选 file 仓库解析。

## 实现与增量选择

入口：`deploy/upgrade-smoke/storage.sh`；固定样例：`storage_upgrade.py`、`fixtures/storage/`。通用 `service_upgrade.prepare` 仅增加旧工程配置钩子，并按样例下一个可用版本追加默认增量；02 默认工程仍使用 V8/V9。没有修改候选 SDK/脚手架制品源码。

旧版业务通过 `ObjectStorage.prepareUpload`、实际预签名 PUT、`completeUpload`、业务事务与 Mapper 保存文档引用；下载经业务 HTTP 签发 URL，再直接 HTTP 读取 S3 内容。候选只合并显式 `StoragePurpose`、绑定/下载用途核对，以及独立样例消费 SDK 的生命周期入口；没有产品升级 API、自动覆盖业务仓库的工具或默认 starter 能力。

旧 V1—V9 全部字节保留，原文件/引用不重建。追加内容如下，完整摘要在 `baseline.json`：

| 新迁移 | 内容 | SHA-256 |
| --- | --- | --- |
| V10 | 02 同样的默认死信表增量 | `2467fd0dfac69176e61a1f717fd65e974fd9cd9a5594b17a122d2c968b8a636a` |
| V11 | 02 同样的历史默认表/列中文注释 | `349d935b0fefe098ccc4a22b334b6c624da15ab612e109f81f03005fc10da9c9` |
| V12 | 实际目标 storage JAR 的 `upgrade-purpose.sql` | `b028bd6619e0a9e245fbe5d80711e2f4b0b831f541d8980efada75e59e83ecf4` |
| V13 | 同 JAR 的 `upgrade-retention.sql` | `f014d92fbbea8eed8eb1d246e6707f907da48719d50a873b7453d9e4e00c1f80` |
| V14 | 项目自有的可信文档引用归类 | `106d35ae84a3ca779917e6d5359b3f9526861e90580783bf40736f247c19e1a8` |
| V15 | 从目标完整 schema 选择 storage 表/列的 COMMENT ON | `08946ac9d3fae7cfe2337a5732ef9aac8ef5543e18c8df5627e49d77818b639f` |

V14 只处理样例明确声明的两个租户，保持 FORCE RLS，并在各自租户上下文匹配文档 `object_id` 与 `tenant_id`，包括逻辑删除引用。不根据扩展名/MIME/文件名猜用途；没有业务引用的旧对象保持 unspecified。真实工程须用自己的可信租户清单与业务引用定义，不复制测试 UUID。已有 purpose 或 retention 的工程按实际缺项继续，不重放；集中说明见 [storage 升级清单](../../docs/upgrades/storage-0.2.0.md)，接入指南、通用升级清单及 CHANGELOG 已连接。

## TDD 与实际观察

1. **缺列红灯**：`04-missing-columns-red/verify-upgraded.log`。发布旧 JAR 创建真实对象与业务引用、原下载成功；停止后候选使用同库读取对象返回 `500 system.error`，运行日志明确缺 `purpose`。
2. **用途归类红灯**：`04-purpose-red/verify-upgraded.log`。补两个 SDK 增量后原元数据可读，但原业务文档下载返回 `400 storage.purposeMismatch`。追加可信引用归类后 `04-first-green/verify-upgraded.log` 首绿通过。
3. **扩展片**：`04-lifecycle-red.log` 先证明未知同扩展名旧文件拒绝普通下载/绑定，逻辑删除引用仍能归类；生命周期 HTTP 观察入口缺失返回 404。补独立样例的最小 SDK 适配后，从发布输入重新生成完整工程执行最终验收。
4. **最终绿灯**：`/private/tmp/v020-upgrade/04-green/verify-upgraded.log`：完整 **verify 1 test，0 failures，0 errors，0 skipped**，包括依赖门禁、NeedBraces、Spotless、编译与打包。一个最高 HTTP 接缝覆盖整个旧→新→再次启动过程；不是以 IO 的成功替代本票。
5. 升级前建立两租户四个已确认文件和一个已上传未确认对象：当前附件、无业务关联文件、逻辑删除业务仍引用的附件、另一租户附件及 PENDING。旧版均使用同一个 `.txt` 扩展名，证明归类依据不是文件扩展名。
6. 升级后四个已有 STORED 对象的 ID、文件名、类型、字节数、确认时间完全一致；当前业务文档的 ID、对象引用、标题一致。两个租户原业务附件与逻辑删除引用对应内容均可下载。旧未知文件元数据保留，但普通用途下载和绑定均返回 `400 storage.purposeMismatch`。
7. 所有旧对象最初保持 STORED/PENDING，retention/expired/purge 时间均为空。旧 PENDING 可继续确认成为 STORED，但不伪造用途或追溯保留期，普通用途下载仍被拒绝。
8. 匿名业务读取 401、只读成员上传 403、另一租户对文件/文档/下载/生命周期/确认均 404。候选拒绝非法扩展名及超过用途上限，容量不变；新上传、确认、绑定和下载通过。
9. 新文件具有约 30 天截止；显式删除变为 EXPIRED，`purgeAfter=expiredAt+7天`，容量继续占用，新的下载与重复确认均 404，既有预签名 URL 仍能下载。宽限内恢复为 STORED，截止重算，内容可读。A 容量从旧 102 字节到新文件后的 123 字节，失效时不释放。
10. `before-file.txt`、`retained-file.txt` 的 SHA-256 均为 `71f451b8270c801b55914a9e06d3edbfe9d2a0b74969d675622fab0dfe53268d`。
11. Flyway 公共接口记录 9→15，原九条的版本、脚本、checksum、执行时间与账号完全相同。三个阶段实际 JDBC 均为 `jdbc:postgresql://localhost:57772/forge_1?loggerLevel=OFF`、schema demo、迁移账号 demo_migrator、运行账号 demo_app；Redis 库和 S3 endpoint/bucket 也保持同一本轮实例。重启后历史不变、原附件仍可下载、未知用途仍拒绝，新恢复对象仍为 STORED。

没有 clean/repair、重建既有库、重写已执行迁移或删除原文件来规避问题。最终运行日志无 ERROR 或调度任务失败；调度租户目录使用已有测试 HTTP 接缝，返回空清单。

## 复现、诊断和边界

```sh
bash scripts/release/candidate.sh use /private/tmp/v020-upgrade/candidate-54115992 \
  bash deploy/upgrade-smoke/storage.sh /private/tmp/v020-upgrade/04-reproduce \
  /private/tmp/v020-upgrade/02-green/published
```

新证据目录须尚不存在。省略最后参数时从真实发布仓库只读下载，需包读取授权。当前 16GiB 机器完整命令通过 `python3 /private/tmp/v020-upgrade/run-heavy.py <命令...>` 串行运行；该协调锁不进入仓库。

最终证据 `/private/tmp/v020-upgrade/04-green/` 包括原/参考/升级工程、实际 JAR、发布来源、`baseline.json`、`http-evidence.json`、生成/构建/验证日志、三阶段 runtime 日志和前后实际下载文件。早期夹具语法诊断为 `04-red.log`；HTTP 红绿快照分别为 `04-missing-columns-red/`、`04-purpose-red/`、`04-first-green/`，扩展红灯为 `04-lifecycle-red.log`。大证据不入库。

轻量检查：Python 3.9 AST、Shell 语法、`git diff --check` 通过。入库 HTTP 测试与生命周期适配夹具和本轮实际 Spotless/编译/执行的文件一致。环境为 JDK 17.0.12、Maven Wrapper 3.9.14、PostgreSQL 17.11、Redis 7、S3 `pgsty/silo:RELEASE.2026-10-18T00-00-00Z`、macOS/Docker Desktop。

本票以既有真实 JWT/JWKS、Redis Membership Grants、服务 HTTP、Flyway 和 S3 接缝验证。没有真实 IAM 登录、网关、Shell 或跨服务引用；MQ/Nacos/远程 Manifest/租户生命周期写保护关闭，不证明实际 Broker 审计投递。未执行到期扫描/七天后物理清理，也未声明完整业务引用检查器或其他 S3 供应商兼容；不通过测试专用时间回拨扩大本票范围。未知用途/无检查器覆盖的安全跳过规则按既有 SDK 契约保留。

未上传目标 Maven/npm 仓库，未操作共享/生产数据或切换环境。候选源码或制品内容变化后须判断影响并重新验证，不能继续沿用不匹配证据。本票只改变消费脚本、样例和升级文档，包来源仍是 54115992。
