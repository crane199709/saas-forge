# 已接入 storage 的工程升级至 0.2.0

先完成 [Business Service 通用升级](business-service-0.2.0.md)，再按本工程实际使用的 storage 结构补差异。默认 starter 不引入 storage；未使用附件或对象存储的工程不增加相关依赖、表和配置。IO 的 storage 依赖迁移不替代独立 storage 文件与业务引用验收。

## 核对真实起点

记录业务源码、SDK 版本、已执行的 Flyway 脚本和 checksum、已有存储列、对象状态及业务引用。通用验收起点是实际发布的 `0.1.0` Maven 制品，不使用本机 install 缓存或本地重建的同版本 SDK。候选来源由 `candidate.json`、源码提交和制品 SHA-256 确认，验收脚本的提交不作为包来源。

已发布 storage `0.1.0` 的 `forge_storage_object` 没有 `purpose`、`retention_expires_at` 和 `expired_at`。实际业务工程可能已做过部分升级，须对照结构和迁移历史选择缺少的步骤，不能只按依赖版本判断。保留已执行的初始迁移，不复制候选完整 `schema.sql` 覆盖旧建表，不执行 Flyway clean/repair。

## 按已有结构选择增量

从**实际消费的目标 storage JAR** 提取 `io/saasforge/sdk/storage/` 下的 SQL，采用业务工程下一个可用迁移号。已经执行的步骤不重复应用，未用的能力不增加。迁移期间暂停业务写入，先在隔离环境演练；升级后的库不承诺能直接退回旧 SDK。

| 当前结构 | 本次所需步骤 | 旧数据语义 |
| --- | --- | --- |
| 缺 `purpose` | 新增迁移复制 `upgrade-purpose.sql` | 旧行填 `unspecified`，随后移除默认值；以后新登记必须显式声明用途。 |
| 已有 `purpose` | 保留原用途迁移与现值 | 不重复加列，也不重置已经明确的用途。 |
| 缺 retention 两列 | 新增迁移复制 `upgrade-retention.sql` | 原 `STORED`/`PENDING` 状态不变，旧截止和失效时间为 NULL，旧文件不追溯应用新保留期。 |
| 两列均已有 | 保留原迁移和现值 | 不重写现有截止、失效或已恢复状态。仅存在其中一列时先审查历史原因，再编写针对缺列的增量。 |
| 旧表/列无中文注释 | 从目标实际 `schema.sql` 选择对应 `COMMENT ON`，追加迁移 | 只补注释，不复制建表、索引和 RLS 语句，不修改旧迁移。 |

`unspecified` 不能声明为 `StoragePurpose`，也不能被普通用途的 `get(purpose, id)` 或 `prepareDownload(purpose, id)` 接受。未知文件的元数据与内容保留，但普通下载返回 `400 storage.purposeMismatch`。不能根据扩展名、文件名、MIME 或猜测设置用途。

只有可信业务引用能证明用途时，才追加**项目自有**显式归类迁移。例如本样例的 `document_files.object_id` 是旧业务在存储确认后写入的附件引用；归类同时匹配对象 ID 和租户，包含逻辑删除的引用行，不迁移没有业务依据的对象。迁移账号受 FORCE RLS 约束，须在正确的租户上下文执行。样例只处理明确记录的两个测试租户；实际工程应从可信租户清单逐租户执行，不复制测试 UUID，不关闭 RLS 或扩大账号权限。

## 合并业务调用和运行配置

业务服务以常量声明 `StoragePurpose`，并在 `prepareUpload`、`write`、下载和业务绑定时显式传用途。扩展名白名单、大小上限和 URL 时长来自业务约定，不由客户端指定；绑定在可写事务中先 `get(purpose, id)` 再保存引用。保留原业务路由、权限码和引用表，不为升级新增生产升级接口或自动覆盖业务文件。

删除旧全局 `forge.storage.download-url-ttl` 配置，改由用途 `downloadUrlTtl` 控制。保留 endpoint、bucket、access-key、secret-key；endpoint 必须供客户端直接访问。storage 现在引入 audit/scheduling，核对基础 MQ 表、`forge-audit` Topic、Redis，以及 `forge.scheduling.iam-uri`/`forge.scheduling.token`，可引用已有 Manifest 配置。完整接入与错误码见[业务接入指南](../business-service-guide.md#按需启用-storage-与镜像)。

新用途可用第五参声明正数 `retentionDays`，四参构造仍表示永久保留。新增文件从申请/写入登记时开始计时；历史截止为 NULL 的文件继续永久保留。`delete(id)` 改为标记失效，七天宽限期内文件继续占用容量，既有预签名 URL 在原有效期内仍可访问。恢复和延长按生命周期契约处理，不把重复确认当作恢复。

业务实现 `FileReferenceChecker` 时，只声明能完整检查的用途，包含逻辑删除引用，批量查询设置超时，不按请求成员权限缩窄引用范围。未知用途或缺检查器覆盖的文件安全跳过自动物理清理。不要为释放容量把未知引用当作无引用；本页升级验收不替代到期治理、供应商兼容或跨服务引用专项。

## 独立验收与诊断

需要 JDK 17、Python 3、Docker 与固定候选。入口复用默认业务升级框架，但单独生成旧 storage 工程并使用其自己的数据库、Redis 库、S3 对象和 HTTP 证据；不接收外部数据库地址。

```sh
bash scripts/release/candidate.sh use /path/to/fixed-candidate \
  bash deploy/upgrade-smoke/storage.sh /path/to/new-storage-evidence \
  /path/to/verified-published-0.1.0-cache
```

证据目录须尚不存在。最后的发布缓存目录可省略；此时只读下载真实发布输入，需要包读取授权。缓存只在原始来源 URL 和 SHA-256 都匹配时复用，跨 origin 的下载重定向不携带凭证。独立 Maven 缓存排除本机全部 Forge 制品，只复用第三方依赖。

验收先以发布旧 SDK 经业务 HTTP 上传真实对象并写业务引用，停止旧进程，再以候选 JAR 在同一数据库、Redis 和 S3 上执行缺少增量。通过业务 HTTP 检查原内容、元数据、引用、用途拒绝、新文件与租户权限；使用 Flyway 公共接口核对原历史、checksum、执行时间和账号不变，再次启动不重复迁移。

生成工程、实际 JAR、`published/provenance.json`、`baseline.json`、`http-evidence.json`、构建与 `runtime-*.log` 留在证据目录。缺列先检查新增迁移，checksum 不符先恢复旧迁移原字节，用途拒绝先核对可信业务关联，不临时放开下载。实际 IAM 登录、网关、Shell、Broker 和非本次 S3 兼容实现另行验证。未向目标制品仓库上传，未操作共享或生产数据。
