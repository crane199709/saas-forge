# AI 用量运营投影

`GET /platform/tenants/{id}/ai-usage?month=2026-11` 使用既有 `platform:tenant:read`；先通过 TenantService 按真实 tenant Data Policy 校验，范围外与不存在均为 404。省略 month 按统一 `forge.business-zone` 当前月查询；该配置现为 Platform 必配项，已有 Nacos 共享配置已包含它。

返回标准 ApiResponse，data 为 `month,businessZone,received,inputTokens,outputTokens,cachedInputTokens,totalTokens,estimatedTokens,turnCount,toolCalls,unknownUsageTurns,days`。`totalTokens=inputTokens+outputTokens`，缓存是输入子集。days 包含 usageDate,version,上述计数与reportedAt。`received=false` 表示尚无投影，不代表确定使用量为零；unknownUsageTurns 非零时估计量与已知token分开显示。本投影供运营查看，AI服务的权威账本负责实时配额。

V10 新增每日累计快照表，属于运营全局投影，不开启租户RLS；HTTP数据范围通过 tenant 主数据校验，接收写入不伪装Operator。消息Handler沿用 `forge-tenant`，Tag为 `AiUsageDailyReported`。两仓各自定义record，JSON契约为 `tenantId,usageDate,businessZone,version,inputTokens,outputTokens,cachedInputTokens,turnCount,toolCalls,unknownUsageTurns,estimatedTokens,reportedAt`。按tenant/day只接受更大持久version整体替换；相同/更旧版本无变化。检查MQ恢复的CurrentTenant等于payload、真实租户存在、业务时区一致、计数非负及缓存/未知子集边界。消息生产者可信性由现有broker ACL与部署身份提供，不将payload当签名。

运营控制台在现有租户详情抽屉提供只读月份查询，对只有tenant:read的Operator仍可见。未知回合提示、已知Token、估计占额、缓存、回合/工具次数与每日最后回报分别展示。查询失败与尚未回报有独立状态，切换租户或关闭抽屉后丢弃旧响应。未新增导航、角色、权限或计费规则。

验证：TenantAiUsageTest覆盖版本重复/乱序、跨日汇总、隔离、未知计量、缺失/错误MQ租户与计数、404/403/DataPolicy。Platform模块完整verify、真实Vue行为测试、构建、样式门禁与macOS1440/1024截图走查均为交付检查。AI仓库PlatformIntegrationIT使用本分支在私有Maven仓库构建的Platform/IAM/Gateway运行包验证真实Outbox→RocketMQ→平台接收→Gateway查询；旧jar不作为新接收端的证据。

平台 `TenantAiUsageCleaner` 实现公开 `TenantDataCleaner`，由 SDK 订阅 TenantDeleted 并在恢复租户、写保护豁免的事务里回调。清理器再次核对 CurrentTenant 与 payload 一致，XML 显式按 tenant_id 逻辑删除投影；重复回调幂等，不影响其他租户。接收端遇到 tenant.purgedAt 已确定最终删除时确认旧快照但不重建投影。AI 四张权威用量表的清理属于 AI 工单 11，本平台单表子项由工单 10 完成。
