# Project / Task Example Context

官方示例代表开发者基于 saas-forge 构建的业务应用。各 Tenant 使用同一套业务服务，在权限相同的情况下享有相同功能，各自的业务数据按 Tenant 隔离；Tenant 与 Tenant Context 的定义归属 [Tenant Access](../saas-forge-services/tenant-access-service/CONTEXT.md)。

## Language

**Project**:
示例业务应用中由某一 Tenant 拥有的项目记录。不同 Tenant 使用同一套项目管理功能，但各自的 Project 记录相互隔离。
_Avoid_: 开发者的代码工程, Git 仓库, 每租户独立应用

**Task**:
某一 Project 内的一项工作，必须且只能属于一个与其同 Tenant 的 Project。当前示例不支持将 Task 移动到其他 Project。
_Avoid_: 独立于 Project 的任务, 跨租户共享任务

**Task Status**:
Task 的工作进展，取值为待办、进行中或已完成。
_Avoid_: Project 状态, Tenant 生命周期状态
