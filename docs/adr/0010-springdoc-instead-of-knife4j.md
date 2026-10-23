# 接口文档用 springdoc-openapi 3.x，不用 Knife4j

Knife4j 最后一次发布是 2024-01 的 4.5.0，不支持 Spring Boot 4 / springdoc 3.x，相关 issue 长期无人处理（核实于 2026-10）。各服务用 springdoc-openapi 3.x 输出 `/v3/api-docs`，网关聚合，UI 先用 springdoc 自带的 Swagger UI，需要时可换 Scalar 等独立部署的 UI。
