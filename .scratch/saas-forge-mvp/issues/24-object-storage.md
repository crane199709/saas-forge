# 24: 对象存储

**What to build:** 开发者通过 SDK 的 S3 抽象上传下载文件，对象 key 自动带 `{tenant_id}/{service}/` 前缀，访问一律经预签名 URL（ADR-0011）。demo 子应用为订单演示附件上传与下载；租户的存储容量受 Quota 约束。

**Blocked by:** 05 租户数据隔离, 20 套餐

**Status:** resolved

- [x] SDK 存储 starter 基于 AWS SDK for Java v2，兼容任意 S3 协议存储
- [x] 生成上传与下载预签名 URL，有效期可配置；key 前缀由 SDK 强制，业务代码无法越过
- [x] 跨租户访问他人对象被拒
- [x] 按租户统计已用存储，超出 Quota 时拒绝新上传
- [x] demo-service 与 demo 子应用演示订单附件上传、列表、下载
- [x] Testcontainers（S3 兼容存储）测试：上传下载成功；越权访问被拒；超配额被拒


## Comments

2026-10-30 实现：
- SDK：`forge-sdk-storage` 基于 AWS SDK for Java v2（BOM `awssdk.version` 2.39.2，只用同步客户端，排除 netty-nio-client）。配置 `forge.storage.endpoint/bucket/access-key/secret-key` 无代码默认值，漏配启动失败；`region`（us-east-1）、`path-style`（true）、`upload-url-ttl`（15m）、`download-url-ttl`（5m）可配。入口 `ObjectStorage`：`prepareUpload(filename, contentType, size)` → `UploadTicket`（对象 ID、PUT 预签名 URL、须携带的请求头、失效时间），`completeUpload(objectId)`，`get`，`prepareDownload` → `DownloadTicket`，`usage`。对象 key 固定为 `{tenant_id}/{spring.application.name}/{objectId}`，业务代码只拿到对象 ID，不能指定 key；大小与内容类型签入上传 URL，内容不一致的上传被存储拒绝（403）。下载以附件返回，`Content-Disposition` 同时带 ASCII 回退名与 UTF-8 文件名。
- 隔离：对象登记在 `forge_storage_object`（RLS + 租户拦截），其他租户的对象 ID 与不存在一样返回 404 `storage.objectNotFound`；签名前再核对 key 前缀。改写预签名 URL 中的 key 会让签名失效。
- Quota：SDK 订阅 `TenantPlanChanged`，按 `version` 写入 `forge_storage_quota`（MiB 换算为字节，NULL 或没有记录表示不限）。申请上传时锁定该行，已用容量 = 已确认对象 + 未过期的上传申请，超出时 409 `storage.quotaExceeded`；确认上传时再校验一次（申请已过期或 Plan 已降级），超出则删除登记与对象。降级不影响已确认的对象。错误码文案放在 core 的 `forge-messages.properties`，服务无需配置。
- 服务接入：把 `io/saasforge/sdk/storage/schema.sql` 复制进 Flyway（demo 为 V8），bucket 须预先创建（本地 compose 新增一次性任务 `s3-init` 创建 `forge`，并为 `s3` 加健康检查）。因 `EventConsumer` 同一事件只允许一个处理器，引入 storage starter 的服务不能再自行声明 `TenantPlanChanged` 的 `EventHandler`。
- demo-service：新功能包 `attachment`（`order_attachments` 表，带标准列，`object_id` 部分唯一索引）。`POST /orders/{id}/attachments/uploads`（order:update，返回上传凭证）、`POST /orders/{id}/attachments`（order:update，确认上传并登记，审计 `order.attach`；同一对象重复登记 409 `attachment.alreadyAttached`）、`GET /orders/{id}/attachments`（order:read）、`GET /orders/{id}/attachments/{attachmentId}/download`（order:read，返回下载 URL）。每个操作先读订单，超出数据范围与订单接口一样 `order.notFound`。清单新增菜单 `demo.attachments`，`forge.manifest.version` 升为 4。
- demo 子应用：新增 `attachments` 页（选订单、选文件上传、列表、下载）。文件以 `fetch` PUT 直传预签名 URL，不带租户 Token；下载 URL 每次点击时现取。
- 验证：demo-service `ObjectStorageTest` 4 项（Testcontainers：PostgreSQL、Redis、silo）——上传下载成功、未上传即确认 409、内容大小不符被存储拒绝、key 前缀为 `{tenant}/demo-service/`、中文附件名；其他租户登记/下载被拒、改写 URL 访问他人对象 403；超出 Quota 的申请与已上传后的申请被拒、旧版本 Plan 事件被忽略、升级后恢复；降级后确认上传被拒且对象被删除。demo 子应用 lint、typecheck、prettier 通过。
- 已知限制：未在浏览器中经网关端到端走通附件页（需整套服务与登录）；租户删除后不清理其对象（删除 S3 文件不可恢复，与冷静期后逻辑删除的约定需另行决定）；预签名 URL 使用 `endpoint`，服务访问存储与浏览器访问存储的地址不同时（容器内部网络）需另行支持公开地址；Quota 事件按消费端方式直接调用处理器，未经真实 RocketMQ。

2026-10-30 补充删除：
- SDK `ObjectStorage.delete(objectId)`：删除登记，事务提交后再删除存储中的文件（回滚不会留下没有文件的登记；文件删除失败只记日志，孤儿文件不计入容量也无法访问），释放存储容量；其他租户的对象 404 `storage.objectNotFound`。
- demo-service `DELETE /orders/{id}/attachments/{attachmentId}`（order:update，审计 `order.detach`）：附件逻辑删除与对象删除同一事务；demo 子应用附件列表新增删除按钮。
- 验证：`ObjectStorageTest` 新增 1 项（其他租户删除被拒、SDK 直接删除他人对象 404、删除后文件从存储消失、下载 404、释放的容量可再次上传），demo-service 共 40 项通过；子应用 lint、typecheck 通过。
