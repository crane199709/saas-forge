# 03: SDK 读取字典与参数

**What to build:** 按 [spec](../spec.md) 4.1 节，业务服务经 SDK 读取当前租户的生效字典与参数，进程内缓存、事件失效、过期兜底，并提供 `@DictionaryValue` 校验。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] iam `/internal/` 接口返回某租户合并后的生效值（网关拒绝 `/internal/`）
- [ ] SDK 门面：按编码读取字典（可选项与全部项）与参数（按声明类型返回）
- [ ] 按租户的进程内缓存；收到 `ReferenceDataChanged` 后清除该租户缓存；过期时间兜底（参考 10 分钟，可配置）
- [ ] Bean Validation 注解 `@DictionaryValue("<字典编码>")`：编码须为当前租户该字典的可选项
- [ ] 新增模块或放入既有模块时不得产生 SDK 循环依赖（`forge-architecture` 规则通过）
- [ ] demo-service 声明字典「客户来源」（可扩展）与参数 `order.auto-close`，订单写入使用 `@DictionaryValue`
- [ ] 测试覆盖：事件到达后读到新值、漏收事件时过期后读到新值、停用项校验失败

## Comments
