# Tenant Access 拥有受控 Tenant Brand Profile

Tenant Access 拥有 Tenant Brand Profile 及其受控品牌素材引用；未建立 Tenant Context 时 Console 使用平台品牌，建立后才原子应用对应 Tenant 的显示名称、Logo、favicon、主色与强调色 Token。Tenant 品牌不得改变共享 Design System 的布局、组件、状态颜色、交互语义或无障碍约束；具备明确品牌管理 Permission 的 Tenant Administrator 可以配置，Platform Administrator 只能按平台安全政策禁用违规素材，不能代替 Tenant 修改。

第 4 阶段随 Tenant 品牌管理引入最小 S3 兼容对象存储能力，品牌素材与第 6 阶段的 Audit 导出文件必须使用分离的存储边界、权限与生命周期策略。本决策有意扩展此前“对象存储仅用于 Audit 导出且第 6 阶段加入”的范围；MVP 计划已同步新边界，相关技术栈与部署说明须由后续阶段规格统一更新。

2026-10-14 进一步确认有效品牌图片可公开读取、管理和绑定仍按 Tenant 授权，素材限定静态 PNG，见 [ADR 0060](0060-brand-assets-are-public-controlled-png-resources.md)。公开图片不改变品牌 Profile、成员或组织管理数据的授权边界。
