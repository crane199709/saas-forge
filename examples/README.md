# 官方示例

官方 Project / Task SaaS 示例用于演示开发者如何接入 saas-forge：各 Tenant 使用同一套业务服务，在权限相同的情况下享有相同功能，各自的业务数据按 Tenant 隔离。领域术语见 [CONTEXT.md](CONTEXT.md)。

## 当前状态

已实现 Project 创建、列表、详情和基于资源版本的修改，以及 Task 创建、分页、详情、版本修改、状态流转和永久删除，详见 [Project Example](project-service/README.md)。相关验收见 [#212](../docs/acceptance/issue-212-acceptance.md) 、[#213](../docs/acceptance/issue-213-acceptance.md) 、[#214](../docs/acceptance/issue-214-acceptance.md) 和 [#215](../docs/acceptance/issue-215-acceptance.md)。Project 删除尚未实现；以下清单是完整目标范围。

## 已确认范围

- Project 与 Task 均提供创建、列表、详情、修改和删除 API。
- Project 包含名称和可选描述；Task 包含标题、可选描述和状态（待办、进行中、已完成）。名称允许重复，标识、创建时间和更新时间由系统维护。
- 一个 Project 可包含多个 Task，也可没有 Task；每个 Task 必须且只能属于一个同 Tenant 的 Project，本次不支持移动 Task 到其他 Project。
- 本次不增加负责人、截止时间、优先级和项目成员权限。
- Project 下仍有 Task 时拒绝删除，必须先删除其 Task；Project 与 Task 均采用永久删除，本次不提供回收站或恢复功能。
- Task 新建时固定为待办；创建后，待办、进行中、已完成之间允许自由切换，包括重新打开已完成任务，无需审批或按顺序推进。
- Project 与 Task 的修改、删除均校验调用方读取的资源版本；资源已被其他请求修改时拒绝过期操作，调用方须重新读取后再提交，不允许后提交者静默覆盖先提交者的变更。
- 业务仅通过 Starter 获取可信 Tenant Context；租户范围表使用事务级 `app.tenant_id` 和 RLS。
- Gateway、日志、Trace、审计、Manifest、Remote 以及 Permission、Feature、Quota 的后续闭环按 [MVP 开发计划](../docs/16-mvp-development-plan.md) 分项推进，不作为本条最小业务 API 已实现的声明。
