# Contracts Context

Contracts 管理跨上下文 Published Language 的版本、兼容性与工程注册；它不拥有各服务的领域事实。

## Language

**Committed Fact Event**:
由其数据权威服务提交后对外传播的不可变事实；它不是命令，也不表示某个跨服务工作流已经成功。
_Avoid_: Message, command, notification

**v1 Contract Baseline**:
仓库中经显式评审后固定且不可修改的 REST、Protobuf 和事件 v1 已发布契约快照；兼容性门禁以全部历史基线拒绝破坏性变更。
_Avoid_: Current contract, generated client, automatic snapshot
