package io.saas.forge.audit.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saas.forge.audit.infrastructure.messaging.TenantAccessEventValidator;
import io.saas.forge.audit.infrastructure.messaging.TenantCreatedEventValidator;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class BrandAssetAuditPostgreSqlIT {
    private static final String TOPIC = "saas.forge.test.tenant-access-service.events";
    private static final String TENANT = "019535d9-0001-7000-8000-000000000003";
    private static final String ASSET = "019535d9-0001-7000-8000-000000000028";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    @Test
    void committedUploadFactIsPersistedOnceUnderAuditApplicationRole() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        admin.execute("CREATE ROLE audit_app LOGIN PASSWORD 'audit-app-brand-test-password'");
        admin.execute("GRANT CONNECT ON DATABASE " + postgres.getDatabaseName() + " TO audit_app");
        admin.execute("GRANT USAGE ON SCHEMA public TO audit_app");
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var app = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), "audit_app", "audit-app-brand-test-password"));
        var json = new ObjectMapper();
        var validator = new TenantAccessEventValidator(json,
                new TenantCreatedEventValidator(json, TOPIC), TOPIC);
        String event = """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000029",
                "source":"urn:saas.forge:tenant-access-service","type":"com.saas.forge.brand.asset-uploaded.v1",
                "subject":"%s","time":"2026-10-14T08:00:00Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/brand-asset-uploaded.v1.schema.json",
                "data":{"tenantId":"%s","assetId":"%s",
                "actorIdentityId":"019535d9-0001-7000-8000-000000000001","kind":"FAVICON"}}
                """.formatted(TENANT, TENANT, ASSET).replace("\n", "");
        var record = validator.validate(TOPIC, TENANT, TenantCreatedEventValidator.CONSUMER_NAME, event).orElseThrow();
        var repository = new JdbcAuditRecordRepository(app);
        assertTrue(repository.consume(TenantCreatedEventValidator.CONSUMER_NAME, record, Instant.now()));
        assertFalse(repository.consume(TenantCreatedEventValidator.CONSUMER_NAME, record, Instant.now()));
        assertEquals(1, app.queryForObject("SELECT count(*) FROM audit_records WHERE action = 'BRAND_ASSET_UPLOADED'",
                Integer.class));
        assertEquals(ASSET, app.queryForObject("""
                SELECT resource_id::text FROM audit_records WHERE action = 'BRAND_ASSET_UPLOADED'
                """, String.class));
        String profileEvent = """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000030",
                "source":"urn:saas.forge:tenant-access-service","type":"com.saas.forge.brand.profile-saved.v1",
                "subject":"%s","time":"2026-10-14T08:01:00Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/brand-profile-saved.v1.schema.json",
                "data":{"tenantId":"%s","actorIdentityId":"019535d9-0001-7000-8000-000000000001",
                "revision":2,"logoAssetId":"%s","faviconAssetId":"%s"}}
                """.formatted(TENANT, TENANT, ASSET, ASSET).replace("\n", "");
        var profileRecord = validator.validate(TOPIC, TENANT,
                TenantCreatedEventValidator.CONSUMER_NAME, profileEvent).orElseThrow();
        assertTrue(repository.consume(TenantCreatedEventValidator.CONSUMER_NAME, profileRecord, Instant.now()));
        assertFalse(repository.consume(TenantCreatedEventValidator.CONSUMER_NAME, profileRecord, Instant.now()));
        assertEquals(1, app.queryForObject("SELECT count(*) FROM audit_records WHERE action = 'BRAND_PROFILE_SAVED'",
                Integer.class));
        assertEquals(TENANT, app.queryForObject("""
                SELECT resource_id::text FROM audit_records WHERE action = 'BRAND_PROFILE_SAVED'
                """, String.class));
        String disabledEvent = """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000031",
                "source":"urn:saas.forge:tenant-access-service","type":"com.saas.forge.brand.asset-disabled.v1",
                "subject":"%s","time":"2026-10-14T08:02:00Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/brand-asset-disabled.v1.schema.json",
                "data":{"tenantId":"%s","assetId":"%s",
                "actorIdentityId":"019535d9-0001-7000-8000-000000000001","kind":"FAVICON"}}
                """.formatted(TENANT, TENANT, ASSET).replace("\n", "");
        var disabledRecord = validator.validate(TOPIC, TENANT,
                TenantCreatedEventValidator.CONSUMER_NAME, disabledEvent).orElseThrow();
        assertTrue(repository.consume(TenantCreatedEventValidator.CONSUMER_NAME, disabledRecord, Instant.now()));
        assertFalse(repository.consume(TenantCreatedEventValidator.CONSUMER_NAME, disabledRecord, Instant.now()));
        assertEquals(1, app.queryForObject("SELECT count(*) FROM audit_records WHERE action = 'BRAND_ASSET_DISABLED'",
                Integer.class));
        assertEquals(ASSET, app.queryForObject("""
                SELECT resource_id::text FROM audit_records WHERE action = 'BRAND_ASSET_DISABLED'
                """, String.class));
        String removedEvent = """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000032",
                "source":"urn:saas.forge:tenant-access-service","type":"com.saas.forge.brand.asset-removed.v1",
                "subject":"%s","time":"2026-10-22T08:02:00Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/brand-asset-removed.v1.schema.json",
                "data":{"tenantId":"%s","assetId":"%s","kind":"FAVICON","reason":"DISABLED"}}
                """.formatted(TENANT, TENANT, ASSET).replace("\n", "");
        var removed = validator.validate(TOPIC, TENANT,
                TenantCreatedEventValidator.CONSUMER_NAME, removedEvent).orElseThrow();
        assertTrue(repository.consume(TenantCreatedEventValidator.CONSUMER_NAME, removed, Instant.now()));
        assertFalse(repository.consume(TenantCreatedEventValidator.CONSUMER_NAME, removed, Instant.now()));
        assertEquals(1, app.queryForObject("SELECT count(*) FROM audit_records WHERE action = 'BRAND_ASSET_REMOVED'",
                Integer.class));
    }
}
