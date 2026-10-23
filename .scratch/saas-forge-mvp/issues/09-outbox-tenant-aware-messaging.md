# 09: Outbox 与租户感知消息

**What to build:** 开发者在业务事务中「发布一个事件」，SDK 保证事件随本地事务原子落库、可靠投递到 RocketMQ，消费端自动恢复租户上下文并幂等去重（ADR-0008、ADR-0012）。demo-service 以「订单已创建」事件演示发布与消费。

**Blocked by:** 05 租户数据隔离

**Status:** ready-for-agent

- [ ] SDK 封装 `rocketmq-client-java` 5.x，不依赖 rocketmq-spring-boot-starter
- [ ] 发布事件写入 Outbox 表，与业务数据同一本地事务；事务回滚则事件不存在
- [ ] 投递器在事务提交后发送，失败重试并有退避；发送成功后标记
- [ ] 消息头携带租户上下文，消费端自动恢复，消费逻辑中租户隔离照常生效
- [ ] 消费端按消息 ID 幂等去重
- [ ] Testcontainers 测试：MQ 不可用期间产生的事件在恢复后全部送达；重复投递同一消息只被处理一次；回滚的事务不产生事件
