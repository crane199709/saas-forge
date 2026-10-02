# 独立业务契约参与统一 Route Catalog 生成

Project / Task Example 拥有自己的业务 OpenAPI，不将业务模型并入平台 v1 契约。经业务 HTTP 契约登记表显式登记后，构建时将其 operation 与平台契约一起生成统一 Route Catalog；Gateway 按目录转发，Example Starter 按同一目录再次认证。业务代码不再手写第二套路由和认证声明，Gateway 认证也不替代服务认证。该决策扩展 ADR 0034 的契约驱动边界。

保留独立契约意味着业务 API 的正式 Client 发布和部署准备仍需单独完成，不能因 Gateway 已生成路由就宣称浏览器可用。选择构建时登记而非运行时任意注册，是为了在发布前拒绝路径冲突、无归属 operation 和认证缺失，避免业务应用动态改变公开攻击面。业务服务的实例地址仍来自服务发现，登记表只记录稳定服务标识和制品内契约路径。

六类成功写事实属于 Example：Project / Task 的创建、修改、删除与本服务 Outbox 同事务提交；Audit 通过单向 Committed Fact Event 保存只追加记录。幂等重放不产生新事实；当前拒绝与失败仅记结构化日志，完整拒绝审计仍由阶段 6 承接。日志和 Trace 导出不包含名称、标题、描述、请求正文或凭据。该切片的后端诊断不能替代阶段 3 的 Tenant Shell / Remote 浏览器验收。
