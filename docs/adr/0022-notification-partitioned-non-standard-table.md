# Notification 使用按月分区的非标准表，到期删除分区

ADR-0016 规定标准表一律逻辑删除、不做物理清除，但 Notification 按收件人展开，写入量随 Membership 数增长，只增不减的表无法长期承受。因此 Notification 与 Audit Event 一样使用按月分区的非标准表，保留 180 天后直接删除整个分区；Notification 只是提醒，真正的事实仍保留在业务数据和 Audit Event 中。

这是 ADR-0016 的明确例外，只适用于 Notification。Todo 和 Announcement 的量级受业务约束，并且状态本身就是业务事实，仍使用标准表和逻辑删除。

## Considered Options

- 标准表加定时批量逻辑删除：符合 ADR-0016，但行不会真正减少，表和索引持续膨胀。
- 标准表加定时批量物理删除：同样破坏 ADR-0016，且大批量 DELETE 的代价高于删除分区。
- 只保存一条通知和目标规则，读取时计算收件人：写入量小，但已读状态仍要按人保存，且收件人会随权限变化，不再是通知发出时的事实。
