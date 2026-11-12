# 05: 在线脱敏与 demo 验收

**What to build:** 按 [spec](../spec.md) 的“实现决策”，在线接口通过 Jackson 序列化器按注解脱敏；demo-service 提供可验收的字段与受控原文查看示例。

**Blocked by:** 02, 03

**Status:** ready-for-agent

- [ ] 序列化器只处理 Manifest 声明的字段；嵌套对象与集合中的字段同样生效
- [ ] 缺规则时整次响应失败；未声明字段保持原样
- [ ] demo-service 增加敏感字段示例（如手机号、身份证），演示导出、在线与日志三种通道
- [ ] demo-service 提供一个专用“查看原文”操作，使用独立 Permission Code 并写审计
- [ ] 测试覆盖：HTTP 响应脱敏、缺规则 500、原文查看权限与审计、未声明字段不变
- [ ] ArchUnit 规则：业务代码不得自行拼接掩码字符串绕过统一引擎

## Comments
