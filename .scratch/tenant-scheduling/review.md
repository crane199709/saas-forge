# tenant-scheduling 代码审查

固定点：`e38555a1c28c99725b4fe03d31ea40e8d5dd6d58`；初审提交：`9bfe75b5ddd4e5017e340319ab2232c4d571b01c`。编码规范与规格符合性由两个独立上下文并行审查。

## Standards

初审发现两项 P3 明确规范偏离：

1. `TaskRegistrar.java:109-110` 直接用 `Binder.bindOrCreate` 创建 `SchedulingProperties`，未按 `docs/agents/coding-standards.md`「配置读取」要求使用 `@EnableConfigurationProperties` 注册并注入。修正为属性 Bean，由 `ObjectProvider` 延迟注入；绑定允许缺省，仅声明 Tenant Task 时在启动阶段校验 IAM 必填项。
2. `TenantMapper.java:31` 的 `countByStatus` 文档未明确 Data Policy 语义，不符合「Mapper 接口的每个方法说明用途及是否受 Data Policy 过滤」的要求。已补充租户目录是全局表、不受 Data Policy 过滤的说明。

基线代码异味无可执行发现。

## Spec

发现 0 项确定缺陷：缺失或部分实现 0 项，范围扩张 0 项，实现错误 0 项。

已核对锁与续期、系统身份、状态复查与失败隔离、cron 时区、内部鉴权分页、platform 条件更新以及 demo 租户阈值与幂等。

初审总计：Standards 2 项，最高 P3；Spec 0 项，无确定缺陷。

## 修复与验证

修复提交：`af63ba0e2b220531961062816d4cdf5f4be48679`，已合入集成分支。新增配置消费者测试先因缺少配置 Bean 失败，修正后通过；原有 Global Task 免 IAM 配置、Tenant Task 缺配置启动失败及令牌脱敏行为保持通过。

最终同 reactor `verify` 通过：调度 SDK 37 项、tenant 3 项、platform 生命周期与架构 5 项；IAM 编译和相关 Spotless 检查通过。两项规范问题均已修复，无遗留发现。
