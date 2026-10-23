# SDK 只支持 MyBatis-Plus，不支持 JPA

租户过滤与 ABAC 数据过滤都需要在 SQL 层深度改写，SDK 基于 MyBatis-Plus（3.5.16+，`mybatis-plus-spring-boot4-starter`）的 `TenantLineInnerInterceptor` 与自研数据权限拦截器实现。同时支持 JPA/Hibernate 会使这部分成本翻倍，因此明确不支持；使用其他持久层的业务服务得不到自动隔离保障。各服务以 Flyway 管理自己的 Schema。
