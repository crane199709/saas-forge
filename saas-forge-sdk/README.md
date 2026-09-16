# SaaS Forge Java SDK

[English](README-en.md)

本目录提供 Java 业务服务接入 SaaS Forge 的首版 BOM、SDK 与 Spring Boot Starter。

## 首版制品

| 制品 | 状态 | 职责 |
|---|---|---|
| `saas-forge-bom` | 发布 | 统一管理下列四个消费者制品的版本 |
| `saas-forge-sdk-core` | 发布 | 从正式 OpenAPI 显式安全子集生成低层 REST Client |
| `saas-forge-sdk-auth` | 发布 | 不可变 Identity、Service Context 和 Token 验证公共契约 |
| `saas-forge-sdk-tenant` | 发布 | 不可变 Tenant Context 快照和强制读取接口 |
| `saas-forge-spring-boot-starter` | 发布 | 基于共享 Route Catalog 的 HTTP 接收端认证与上下文装配 |

Permission、Feature、Quota 与 Audit SDK 目前只是后续阶段的 Reactor 占位模块：不进入 BOM、不成为 Starter 传递依赖，也不发布到 Maven Central。Starter 运行所需的 `saas-forge-http-route-catalog` 是可发布的内部支撑制品，不由业务应用直接声明。

## Maven 依赖

消费者导入 BOM 后，只需声明 Starter，无需为四个受支持制品分别填写版本：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.crane199709</groupId>
            <artifactId>saas-forge-bom</artifactId>
            <version>${saas-forge.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.crane199709</groupId>
        <artifactId>saas-forge-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

Starter 默认装配 Spring Security Resource Server Bearer 过滤器、IAM JWKS 公钥缓存与 Redis 撤销检查；应用无需自行编写这些适配器。Starter 包含平台既有 Nacos Discovery 接入，通过 `iam-service` 服务发现获取 JWKS，不配置下游实例地址。

必需配置包括 `spring.application.name`（与 Route Catalog 归属一致）、`security.jwt.issuer`、`saas.forge.environment`，以及环境对应的 Nacos Discovery 和 Spring Data Redis 连接配置。凭据由环境变量、Secret 或受限本地文件注入。公钥只接受 RS256；缓存 5 分钟，`saas.forge.authentication.jwks.refresh-interval` 默认 `10s`、`saas.forge.authentication.jwks.wait-timeout` 默认 `2s`，两项必须为正的有限时长。刷新按实例合并；缓存过期无法更新或撤销状态不确定时返回 503，不延长旧缓存。

用户路由拒绝查询参数和 JSON 中的 `tenantId`、`tenant_id`、`tenant`、`currentTenantId` 等保留别名，服务路由的正式 Tenant Operation Target 不受此限制。用户 JSON 检查使用有界读取，`saas.forge.authentication.max-json-bytes` 默认 `1048576`（1 MiB），必须为正整数且小于 `Integer.MAX_VALUE`；超限返回 `413 / PAYLOAD_TOO_LARGE`。业务契约仍须拒绝其他未声明字段，不把自定义别名解释为身份。

默认认证依赖自动加入 `/actuator/health/readiness`，不加入 Liveness。缺少必需配置启动失败；IAM/Redis 暂不可用时进程保留，由 Readiness 探测驱动恢复检查。部署必须按 Readiness 结果控制业务流量，直连接收端仍逐请求失败关闭。

业务代码通过构造器注入只读访问器，不接触 Starter 内部的 Spring Security Principal：

```java
final class CurrentTenantService {
    private final IdentityContextAccessor identities;
    private final TenantContextAccessor tenants;

    CurrentTenantService(IdentityContextAccessor identities, TenantContextAccessor tenants) {
        this.identities = identities;
        this.tenants = tenants;
    }

    TenantContextSnapshot requireCurrent() {
        IdentityContext identity = identities.current().orElseThrow();
        TenantContextSnapshot tenant = tenants.requireCurrent();
        if (!identity.identityId().equals(tenant.identityId())) {
            throw new IllegalStateException("Identity 与 Tenant Context 不一致");
        }
        return tenant;
    }
}
```

已有显式适配方式保留兼容：如应用提供自定义认证适配器，必须完整提供 `UserAccessTokenSignatureVerifier`、`UserAccessTokenContextRevocationChecker`、`ServiceAccessTokenSignatureVerifier` 与 `ServiceAccessTokenRevocationChecker`，不能部分混用以形成允许型回退。常规业务接入应使用默认适配；测试夹具的内存实现仅用于隔离测试。

## REST Client 安全边界

`saas-forge-sdk-core` 只从正式 OpenAPI v1 中标记为 `x-saas.forge-java-sdk: true` 的 operation 生成代码。临时过滤视图和生成源码只存在于 `target`，不能独立编辑。浏览器登录、刷新、Password Setup、Context Selection、登出和 Tenant Context Switch 不进入 Java API，也不会暴露 HttpOnly Cookie、`Origin` 或 Fetch Metadata 参数。

消费者必须显式设置 Gateway 地址，并提供 operation 所需的 Basic 或 Bearer 凭证。默认地址为不可部署的 `https://api.example.invalid`；首版不提供自动重试、熔断、领域 façade 或完整 Problem Details 异常层。

## 发布门禁

[`public-api-allowlist.json`](public-api-allowlist.json) 精确记录四个消费者制品允许的公共 package 和类型。Maven 验证会检查 BOM、Starter、发布白名单、公共签名、JAR 内容、实现引用和传递依赖，拒绝内部 Protobuf、gRPC、数据库、MyBatis、Repository、迁移实现及浏览器安全参数泄漏。新增公共类型必须先经过明确的 allowlist 变更。

首个正式版本发布前不设置虚构的 Java 二进制兼容基线；后续版本将以真实发布制品进行比较。

## 外部消费者验收

[`sdk-external-consumer`](../test-support/saas-forge-external-consumer-fixture) 使用独立 Spring Boot parent，不继承本仓库父 POM 或其 `dependencyManagement`。它只通过 BOM 和 Starter 接入 saas-forge，并在专用测试 Route Catalog overlay 下以真实 HTTP 验证 Tenant Context、默认拒绝、上下文清理和启动失败行为：

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Psdk-external-consumer-acceptance \
  -pl :saas-forge-external-consumer-fixture,:saas-forge-quality-gates \
  -am verify
```

该验收只证明本地 Reactor 中实际运行的 SDK/Starter 消费者边界，不等同于 Maven Central 发布验证，也不替代 `scripts/verify-platform-mechanism-e2e.sh` 的 Gateway-to-Starter 完整基础设施验收。
