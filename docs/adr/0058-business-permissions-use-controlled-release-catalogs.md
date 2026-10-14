# 业务 Permission 通过受控发布清单注册

2026-10-14 第 4 阶段设计访谈确认：业务应用随发布提供版本化 Permission 清单，由受控部署流程导入权威目录。MVP 选择发布时确定能力目录，而非允许普通运行实例调用接口自动注册，以保持权限代码及其服务端执行语义的来源可追溯，并避免新增运行实例的动态注册授权边界。

Manifest 的 Permission 与菜单声明只引用能力，不构成 Permission 注册。Tenant 管理员仍只分配目录中的已有 Permission；本决策不改变管理员自动获得当前及未来有效业务 Permission 的规则。

业务 Permission 可显式停用与恢复；停用对所有角色生效，包括 Tenant Administrator，原角色绑定保留，恢复后重新生效。代码及操作语义不可重新解释或复用；清单缺少旧条目不隐式删除或停用，须显式声明变更。清单格式与导入契约仍待冻结，不表示注册工具已实现。

第 4 阶段官方 Example 目录对应已实现的 Project/Task 各自 `create/list/read/update/delete`，共 10 个 operation；`example:project:export` 与真实导出、Feature 联合交付留给第 5 阶段，不提前注册没有执行入口的能力。
