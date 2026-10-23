# 版本锁定 Spring Boot 4.0.x，暂不升级 4.1

锁定 JDK 17、Spring Boot 4.0.x 最新补丁（4.0.9）、Spring Cloud 2025.1.4、Spring Cloud Alibaba 2025.1.0.0。Boot 4.1 已发布，但 SCA 2025.1.0.0 只针对 Boot 4.0 / Cloud 2025.1，未见支持 4.1 的版本，因此在 SCA 正式支持前不升级。4.0.9 + 2025.1.4 的组合未经官方兼容表明确列出，需在工程骨架阶段以启动与 Nacos 注册验证。
