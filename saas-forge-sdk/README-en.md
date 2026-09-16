# SaaS Forge Java SDK

[简体中文](README.md)

This directory contains the first-release BOM, Java SDKs, and Spring Boot Starter for business services integrating with SaaS Forge.

## First-release artifacts

| Artifact | Status | Responsibility |
|---|---|---|
| `saas-forge-bom` | Published | Manages one compatible version for the four consumer artifacts below |
| `saas-forge-sdk-core` | Published | Low-level REST client generated from an explicitly safe OpenAPI subset |
| `saas-forge-sdk-auth` | Published | Public contracts for immutable Identity and Service Contexts and token verification |
| `saas-forge-sdk-tenant` | Published | Immutable Tenant Context snapshots and mandatory access |
| `saas-forge-spring-boot-starter` | Published | Route Catalog-driven HTTP receiver authentication and context wiring |

The Permission, Feature, Quota, and Audit SDKs are Reactor placeholders for later stages. They are absent from the BOM and Starter dependency graph and are skipped during Maven Central publication. The Starter's `saas-forge-http-route-catalog` dependency is a publishable internal support artifact and is not declared directly by business applications.

## Maven dependencies

Import the BOM and declare only the Starter. The four supported consumer artifacts do not need individual versions:

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

The Starter supplies the Spring Security Resource Server Bearer filter, IAM JWKS caching and Redis revocation checks. Its Nacos Discovery integration resolves `iam-service`; applications do not configure downstream instance URLs or implement these adapters.

Configure `spring.application.name` to match Route Catalog ownership, `security.jwt.issuer`, `saas.forge.environment`, and the environment-specific Nacos Discovery and Spring Data Redis connections. Inject credentials through environment variables, Secrets or restricted local files. Only RS256 is accepted. Keys expire after five minutes; `saas.forge.authentication.jwks.refresh-interval` defaults to `10s` and `saas.forge.authentication.jwks.wait-timeout` to `2s`. Both must be positive finite durations. Concurrent refreshes share one request per instance. Expired keys and indeterminate revocation status fail closed with 503; failures never extend cache validity.

User routes reject reserved Tenant aliases in query parameters and JSON, including `tenantId`, `tenant_id`, `tenant` and `currentTenantId`. Explicit Tenant Operation Targets on service routes remain supported. User JSON is read with a bounded buffer: `saas.forge.authentication.max-json-bytes` defaults to `1048576` (1 MiB), must be positive and less than `Integer.MAX_VALUE`, and returns `413 / PAYLOAD_TOO_LARGE` when exceeded. Business contracts must still reject undeclared fields and must not interpret custom aliases as identity.

Authentication dependencies are automatically included in `/actuator/health/readiness`, not Liveness. Missing required configuration fails startup. Temporary IAM/Redis outages leave the process alive; readiness probes check for recovery. Deployments must route traffic according to Readiness. Direct receiver requests still enforce authentication on every request.

Business code constructor-injects the read-only accessors and never depends on the Starter's internal Spring Security principal:

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
            throw new IllegalStateException("Identity and Tenant Context do not match");
        }
        return tenant;
    }
}
```

Existing explicit adapters remain supported as a complete set: `UserAccessTokenSignatureVerifier`, `UserAccessTokenContextRevocationChecker`, `ServiceAccessTokenSignatureVerifier` and `ServiceAccessTokenRevocationChecker`. Partial custom sets fail startup rather than falling back to permissive behavior. Normal business integration uses the defaults; in-memory fixture adapters are for isolated tests only.

## REST client security boundary

`saas-forge-sdk-core` generates code only for formal OpenAPI v1 operations marked `x-saas.forge-java-sdk: true`. The filtered view and generated sources exist only under `target` and are not independent contracts. Browser login, refresh, Password Setup, Context Selection, logout, and Tenant Context Switch are excluded, as are HttpOnly Cookie, `Origin`, and Fetch Metadata parameters.

Consumers explicitly configure the Gateway address and provide the Basic or Bearer credential required by each operation. The default `https://api.example.invalid` address cannot be deployed. Automatic retries, circuit breakers, domain façades, and a complete Problem Details exception layer are outside the first release.

## Release gates

[`public-api-allowlist.json`](public-api-allowlist.json) records the exact public packages and types allowed in the four consumer artifacts. Maven verification checks the BOM, Starter, publication whitelist, public signatures, JAR contents, implementation references, and transitive dependencies. It rejects internal Protobuf, gRPC, database, MyBatis, Repository, migration, and browser-security leakage. Every new public type requires an explicit allowlist change.

No invented Java binary-compatibility baseline is used before the first formal release. Later versions will compare against the actual published artifact.

## External consumer acceptance

[`sdk-external-consumer`](../test-support/saas-forge-external-consumer-fixture) uses its own Spring Boot parent and does not inherit this repository's parent POM or `dependencyManagement`. It integrates with saas-forge only through the BOM and Starter, then uses real HTTP under a dedicated test Route Catalog overlay to verify Tenant Context, fail-closed behavior, request cleanup, and startup failure:

```bash
./mvnw --batch-mode --no-transfer-progress \
  -Psdk-external-consumer-acceptance \
  -pl :saas-forge-external-consumer-fixture,:saas-forge-quality-gates \
  -am verify
```

This acceptance proves only the SDK/Starter consumer boundary actually exercised in the local Reactor. It is not Maven Central publication verification and does not replace the full-infrastructure Gateway-to-Starter acceptance in `scripts/verify-platform-mechanism-e2e.sh`.
