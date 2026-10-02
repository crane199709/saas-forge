# Remote Delivery 独立拥有 Manifest 生命周期

阶段 3 的 Manifest 需要权威注册、审核、启用与拒绝事实。新增独立 Remote Delivery 服务与逻辑数据库拥有这些事实，复用 IAM 的 Client Credentials 和 Platform Role 权威；Gateway 保持受控浏览器入口、认证与转发职责。2026-10-02 经用户确认采用此方案，以新增部署单元和配置维护成本换取清晰的事实归属，避免向 Gateway 引入领域持久化与审批职责。

这项决策将原有“四个领域服务”的部署目标扩展为五个领域服务；Example 继续为独立业务应用。首版只实现阶段 3 所需的最小生命周期，完整升级、禁用、回退和多 Remote 治理仍属于阶段 7。决策记录不代表服务实现或浏览器验收已完成。
