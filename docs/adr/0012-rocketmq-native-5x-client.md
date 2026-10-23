# RocketMQ 使用原生 5.x 客户端，由 SDK 封装

`rocketmq-spring-boot-starter` 未声明支持 Spring Boot 4，SCA 的 Stream RocketMQ binder 声称支持但未经验证。SDK 直接封装不依赖 Spring 的 `rocketmq-client-java` 5.x（gRPC），避免与 Spring 版本耦合，并由 SDK 统一在消息头透传租户上下文、在消费端恢复。
