package io.saas.forge.tenantaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saas.forge.tenantaccess.application.brand.BrandProfileChange;
import io.saas.forge.tenantaccess.application.brand.BrandProfileService;
import io.saas.forge.tenantaccess.application.brand.BrandAssetDisabledEventFactory;
import io.saas.forge.tenantaccess.application.brand.BrandAssetPolicyService;
import io.saas.forge.tenantaccess.application.brand.BrandAssetCleanupService;
import io.saas.forge.tenantaccess.application.brand.BrandAssetService;
import io.saas.forge.tenantaccess.application.brand.BrandAssetEventFactory;
import io.saas.forge.tenantaccess.application.brand.BrandUploadRecoveryService;
import io.saas.forge.tenantaccess.application.brand.BrandPng;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.brand.BrandAssetRemovedEventFactory;
import io.saas.forge.tenantaccess.application.brand.BrandObjectStore;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcBrandAssetRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcBrandProfileRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class BrandAssetMigrationIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("saas.forge")
            .withUsername("saas.forge_admin")
            .withPassword("admin-password")
            .withEnv("IAM_MIGRATOR_PASSWORD", "iam-migrator-password")
            .withEnv("IAM_APP_PASSWORD", "iam-app-password")
            .withEnv("TENANT_ACCESS_MIGRATOR_PASSWORD", "tenant-access-migrator-password")
            .withEnv("TENANT_ACCESS_APP_PASSWORD", "tenant-access-app-password")
            .withEnv("ENTITLEMENT_MIGRATOR_PASSWORD", "entitlement-migrator-password")
            .withEnv("ENTITLEMENT_APP_PASSWORD", "entitlement-app-password")
            .withEnv("AUDIT_MIGRATOR_PASSWORD", "audit-migrator-password")
            .withEnv("AUDIT_APP_PASSWORD", "audit-app-password")
            .withCopyFileToContainer(MountableFile.forHostPath(repositoryRoot().resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    @Test
    void failedDatabaseAndS3DeletionLeaveDurableUploadCleanup() throws Exception {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Orphan upload', 'ACTIVE') RETURNING id", UUID.class);
        UUID identity = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID membership = migrator.queryForObject(
                "INSERT INTO memberships (tenant_id, identity_id, membership_status) VALUES (?, ?, 'ENABLED') RETURNING id",
                UUID.class, tenant, identity);
        var context = new TenantContextSnapshot(identity, membership, tenant);
        var ids = new UuidV7Generator(Clock.systemUTC(), new SecureRandom());
        AtomicBoolean deleteFails = new AtomicBoolean(true);
        java.util.Set<String> stored = new java.util.HashSet<>();
        BrandObjectStore objects = new BrandObjectStore() {
            public void put(String key, byte[] png) { stored.add(key); }
            public byte[] get(String key) { throw new UnsupportedOperationException(); }
            public void delete(String key) {
                if (deleteFails.get()) throw new IllegalStateException("S3 delete unavailable");
                stored.remove(key);
            }
        };
        var authority = org.mockito.Mockito.mock(
                io.saas.forge.tenantaccess.application.authorization.PermissionAuthority.class);
        org.mockito.Mockito.when(authority.allowed(context, "system:brand:manage")).thenReturn(true);
        var assetService = new BrandAssetService(authority, new JdbcBrandAssetRepository(jdbc), objects,
                new BrandAssetEventFactory(new ObjectMapper(), ids, Clock.systemUTC(), "brand.events"),
                event -> { throw new IllegalStateException("outbox unavailable"); }, ids, tx);
        var pngBytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), "png", pngBytes);
        assertThatThrownBy(() -> assetService.upload(context, BrandPng.Kind.FAVICON, pngBytes.toByteArray()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("outbox unavailable");
        assertThat(stored).hasSize(1);
        assertThat(migrator.queryForObject("SELECT count(*) FROM brand_upload_intents WHERE tenant_id = ?",
                Integer.class, tenant)).isEqualTo(1);
        deleteFails.set(false);
        var recovery = new BrandUploadRecoveryService(new JdbcBrandAssetRepository(jdbc), objects, tx,
                Clock.offset(Clock.systemUTC(), java.time.Duration.ofMinutes(6)));
        assertThat(recovery.cleanNext()).isTrue();
        assertThat(stored).isEmpty();
        assertThat(migrator.queryForObject("SELECT count(*) FROM brand_upload_intents WHERE tenant_id = ?",
                Integer.class, tenant)).isZero();
        assertThat(migrator.queryForObject("SELECT count(*) FROM tenant_brand_assets WHERE tenant_id = ?",
                Integer.class, tenant)).isZero();
    }

    @Test
    void cleanupSelectionCannotRemoveAnAssetBoundByConcurrentProfileSave() throws Exception {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Binding race', 'ACTIVE') RETURNING id", UUID.class);
        UUID logo = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID favicon = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                VALUES (?, ?, 'LOGO', ?, 80, 32, 32), (?, ?, 'FAVICON', ?, 80, 32, 32)
                """, logo, tenant, tenant + "/" + logo + ".png",
                favicon, tenant, tenant + "/" + favicon + ".png");
        // 本类共享 Testcontainer；未来时钟只能看到本场景的候选素材。
        migrator.update("UPDATE tenant_brand_assets SET status = 'REMOVED' WHERE tenant_id <> ? AND status <> 'REMOVED'",
                tenant);
        var profile = new BrandProfileChange("Bound", "/brands/" + logo,
                "/brands/" + favicon, "#123456", "#234567");
        var saved = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var save = workers.submit(() -> tx.execute(status -> {
                new JdbcBrandProfileRepository(jdbc).save(tenant, 0, profile);
                saved.countDown();
                try { assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return true;
            }));
            assertThat(saved.await(5, TimeUnit.SECONDS)).isTrue();
            var claim = workers.submit(() -> tx.execute(status -> new JdbcBrandAssetRepository(jdbc)
                    .claim(java.time.Instant.now().plus(java.time.Duration.ofHours(25)))));
            release.countDown();
            assertThat(save.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(claim.get(10, TimeUnit.SECONDS)).isEmpty();
            assertThat(migrator.queryForObject("SELECT status FROM tenant_brand_assets WHERE id = ?", String.class, logo))
                    .isEqualTo("ACTIVE");
            assertThat(migrator.queryForObject("SELECT status FROM tenant_brand_assets WHERE id = ?", String.class, favicon))
                    .isEqualTo("ACTIVE");
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void expiredUnboundAndRetiredReferencesCannotBeSavedEvenBeforeWorkerClaimsThem() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var app = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Expired brand', 'ACTIVE') RETURNING id", UUID.class);
        UUID expired = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID favicon = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height,
                    uploaded_at)
                VALUES (?, ?, 'LOGO', ?, 80, 32, 32, now() - interval '25 hours'),
                    (?, ?, 'FAVICON', ?, 80, 32, 32, now())
                """, expired, tenant, tenant + "/" + expired + ".png",
                favicon, tenant, tenant + "/" + favicon + ".png");
        var profiles = new JdbcBrandProfileRepository(app);
        var change = new BrandProfileChange("Expired", "/brands/" + expired,
                "/brands/" + favicon, "#123456", "#234567");
        assertThatThrownBy(() -> tx.execute(status -> profiles.save(tenant, 0, change)))
                .isInstanceOf(BrandProfileService.AssetUnavailable.class);
        migrator.update("UPDATE tenant_brand_assets SET ever_bound_at = now() - interval '8 days', "
                + "retired_at = now() - interval '8 days' WHERE id = ?", expired);
        assertThatThrownBy(() -> tx.execute(status -> profiles.save(tenant, 0, change)))
                .isInstanceOf(BrandProfileService.AssetUnavailable.class);
        assertThat(migrator.queryForObject("SELECT count(*) FROM tenant_brand_profiles WHERE tenant_id = ?",
                Integer.class, tenant)).isZero();
        migrator.update("UPDATE tenant_brand_assets SET status = 'REMOVED' WHERE id = ?", expired);
    }

    @Test
    void failedObjectDeletionIsRetriedWithoutPublishingSuccessUntilRemoved() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Retry tenant', 'ACTIVE') RETURNING id", UUID.class);
        UUID asset = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height,
                    uploaded_at)
                VALUES (?, ?, 'LOGO', ?, 80, 32, 32, now() - interval '25 hours')
                """, asset, tenant, tenant + "/" + asset + ".png");
        var deleted = new java.util.concurrent.atomic.AtomicBoolean();
        BrandObjectStore objects = new BrandObjectStore() {
            public void put(String key, byte[] png) { throw new UnsupportedOperationException(); }
            public byte[] get(String key) { throw new UnsupportedOperationException(); }
            public void delete(String key) {
                if (!deleted.getAndSet(true)) throw new IllegalStateException("S3 unavailable");
            }
        };
        var now = java.time.Instant.now();
        var clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        var factory = new BrandAssetRemovedEventFactory(new ObjectMapper(),
                new UuidV7Generator(clock, new SecureRandom()), clock,
                "saas.forge.test.tenant-access-service.events");
        var cleanup = new BrandAssetCleanupService(new JdbcBrandAssetRepository(jdbc), objects,
                event -> jdbc.update("""
                        INSERT INTO tenant_access_outbox_events
                            (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        """, event.eventId(), event.tenantId(), java.sql.Timestamp.from(event.occurredAt()),
                        event.topic(), event.orderingKey(), event.traceId(), event.eventSnapshot(),
                        java.sql.Timestamp.from(event.occurredAt())),
                factory, new TransactionTemplate(manager), clock);
        assertThatThrownBy(cleanup::cleanNext).isInstanceOf(IllegalStateException.class);
        assertThat(migrator.queryForObject("SELECT status FROM tenant_brand_assets WHERE id = ?", String.class, asset))
                .isEqualTo("REMOVING");
        assertThat(jdbc.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), asset)).isEmpty();
        assertThat(migrator.queryForObject("SELECT count(*) FROM tenant_access_outbox_events", Integer.class))
                .isZero();
        cleanup = new BrandAssetCleanupService(new JdbcBrandAssetRepository(jdbc), objects,
                event -> jdbc.update("""
                        INSERT INTO tenant_access_outbox_events
                            (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        """, event.eventId(), event.tenantId(), java.sql.Timestamp.from(event.occurredAt()),
                        event.topic(), event.orderingKey(), event.traceId(), event.eventSnapshot(),
                        java.sql.Timestamp.from(event.occurredAt())),
                factory, new TransactionTemplate(manager), Clock.offset(clock, java.time.Duration.ofMinutes(6)));
        assertThat(cleanup.cleanNext()).isTrue();
        assertThat(migrator.queryForObject("SELECT status FROM tenant_brand_assets WHERE id = ?", String.class, asset))
                .isEqualTo("REMOVED");
        assertThat(migrator.queryForObject("""
                SELECT count(*) FROM tenant_access_outbox_events
                WHERE event_snapshot->>'type' = 'com.saas.forge.brand.asset-removed.v1'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void expiredAssetsAreClaimedOnlyAfterTheirOwnDeadlineAndCurrentBindingSurvives() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var app = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Cleanup tenant', 'ACTIVE') RETURNING id", UUID.class);
        UUID activeLogo = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID oldLogo = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID favicon = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID disabled = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        for (UUID id : java.util.List.of(activeLogo, oldLogo, favicon, disabled)) {
            migrator.update("""
                    INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                    VALUES (?, ?, ?, ?, 80, 32, 32)
                    """, id, tenant, id.equals(favicon) ? "FAVICON" : "LOGO", tenant + "/" + id + ".png");
        }
        var profiles = new JdbcBrandProfileRepository(app);
        var initial = new BrandProfileChange("Brand", "/brands/" + oldLogo,
                "/brands/" + favicon, "#123456", "#234567");
        tx.execute(status -> profiles.save(tenant, 0, initial));
        var replacement = new BrandProfileChange("Brand", "/brands/" + activeLogo,
                "/brands/" + favicon, "#123456", "#234567");
        tx.execute(status -> profiles.save(tenant, 1, replacement));
        migrator.update("UPDATE tenant_brand_assets SET uploaded_at = now() - interval '25 hours' WHERE id = ?", activeLogo);
        migrator.update("UPDATE tenant_brand_assets SET retired_at = now() - interval '8 days' WHERE id = ?", oldLogo);
        migrator.update("UPDATE tenant_brand_assets SET status = 'DISABLED', disabled_at = now() - interval '8 days' WHERE id = ?", disabled);
        UUID first = tx.execute(status -> app.queryForObject("SELECT asset_id FROM claim_expired_brand_asset(?)",
                UUID.class, java.sql.Timestamp.from(java.time.Instant.now())));
        UUID second = tx.execute(status -> app.queryForObject("SELECT asset_id FROM claim_expired_brand_asset(?)",
                UUID.class, java.sql.Timestamp.from(java.time.Instant.now())));
        assertThat(java.util.Set.of(first, second)).containsExactlyInAnyOrder(oldLogo, disabled);
        assertThat(migrator.queryForObject("SELECT status FROM tenant_brand_assets WHERE id = ?", String.class, activeLogo))
                .isEqualTo("ACTIVE");
        assertThat(app.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), first)).isEmpty();
        migrator.update("UPDATE tenant_brand_assets SET status = 'REMOVED' WHERE id IN (?, ?)", first, second);
    }

    @Test
    void platformPolicyFactIsStoredWithDisableAndFailedOutboxRollsItBack() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Outbox tenant', 'ACTIVE') RETURNING id", UUID.class);
        UUID actor = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID first = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID second = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                VALUES (?, ?, 'LOGO', ?, 80, 32, 32), (?, ?, 'FAVICON', ?, 80, 32, 32)
                """, first, tenant, tenant + "/" + first + ".png", second, tenant,
                tenant + "/" + second + ".png");
        var factory = new BrandAssetDisabledEventFactory(new ObjectMapper(),
                new UuidV7Generator(Clock.systemUTC(), new SecureRandom()),
                "saas.forge.test.tenant-access-service.events");
        var policy = new BrandAssetPolicyService(new JdbcBrandAssetRepository(jdbc), event -> {
            jdbc.update("""
                    INSERT INTO tenant_access_outbox_events
                        (event_id, tenant_id, occurred_at, topic, ordering_key, trace_id, event_snapshot, next_attempt_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, event.eventId(), event.tenantId(), java.sql.Timestamp.from(event.occurredAt()), event.topic(),
                    event.orderingKey(), event.traceId(), event.eventSnapshot(),
                    java.sql.Timestamp.from(event.occurredAt()));
        }, factory);
        Boolean firstChanged = tx.execute(status -> policy.disable(actor, first).changed());
        Boolean replayChanged = tx.execute(status -> policy.disable(actor, first).changed());
        assertThat(firstChanged).isTrue();
        assertThat(replayChanged).isFalse();
        assertThat(migrator.queryForObject("""
                SELECT count(*) FROM tenant_access_outbox_events
                WHERE event_snapshot->>'type' = 'com.saas.forge.brand.asset-disabled.v1'
                AND tenant_id = ?
                """, Integer.class, tenant)).isEqualTo(1);
        assertThat(migrator.queryForObject("SELECT status FROM tenant_brand_assets WHERE id = ?", String.class, first))
                .isEqualTo("DISABLED");
        var failing = new BrandAssetPolicyService(new JdbcBrandAssetRepository(jdbc),
                event -> { throw new IllegalStateException("outbox unavailable"); }, factory);
        assertThatThrownBy(() -> tx.execute(status -> failing.disable(actor, second)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(migrator.queryForObject("SELECT status FROM tenant_brand_assets WHERE id = ?", String.class, second))
                .isEqualTo("ACTIVE");
    }

    @Test
    void platformDisableIsPermanentAndAuthoritativeContextFallsBackAsOneProfile() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        var app = new JdbcTemplate(new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password"));
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Policy tenant', 'ACTIVE') RETURNING id", UUID.class);
        UUID other = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Other tenant', 'ACTIVE') RETURNING id", UUID.class);
        UUID identity = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID membership = migrator.queryForObject("""
                INSERT INTO memberships (tenant_id, identity_id, membership_status)
                VALUES (?, ?, 'ENABLED') RETURNING id
                """, UUID.class, tenant, identity);
        UUID logo = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID favicon = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID otherLogo = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID actor = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                VALUES (?, ?, 'LOGO', ?, 80, 32, 32), (?, ?, 'FAVICON', ?, 80, 32, 32),
                    (?, ?, 'LOGO', ?, 80, 32, 32)
                """, logo, tenant, tenant + "/" + logo + ".png", favicon, tenant,
                tenant + "/" + favicon + ".png", otherLogo, other, other + "/" + otherLogo + ".png");
        migrator.update("""
                INSERT INTO tenant_brand_profiles
                    (tenant_id, display_name, logo_url, favicon_url, primary_color, accent_color)
                VALUES (?, 'Policy brand', ?, ?, '#123456', '#234567')
                """, tenant, "/brands/" + logo, "/brands/" + favicon);
        assertThat(app.queryForObject("SELECT brand_display_name FROM list_accessible_membership_contexts(?)",
                String.class, identity)).isEqualTo("Policy brand");
        assertThat(app.queryForObject("SELECT changed FROM disable_brand_asset(?, ?)",
                Boolean.class, logo, actor)).isTrue();
        assertThat(app.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), logo)).isEmpty();
        assertThat(app.queryForObject("SELECT brand_display_name FROM list_accessible_membership_contexts(?)",
                String.class, identity)).isNull();
        assertThat(app.queryForObject("SELECT brand_logo_url FROM list_accessible_membership_contexts(?)",
                String.class, identity)).isNull();
        assertThat(app.queryForObject("SELECT brand_primary_color FROM list_accessible_membership_contexts(?)",
                String.class, identity)).isNull();
        assertThat(app.queryForObject("SELECT changed FROM disable_brand_asset(?, ?)",
                Boolean.class, logo, actor)).isFalse();
        assertThat(app.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), otherLogo)).hasSize(1);
        assertThat(app.queryForObject("SELECT count(*) FROM tenant_brand_assets", Integer.class)).isZero();
        assertThat(migrator.queryForObject("SELECT logo_url FROM tenant_brand_profiles WHERE tenant_id = ?",
                String.class, tenant)).isEqualTo("/brands/" + logo);
        assertThat(migrator.queryForObject("SELECT disabled_by FROM tenant_brand_assets WHERE id = ?",
                UUID.class, logo)).isEqualTo(actor);
        assertThatThrownBy(() -> migrator.update("UPDATE tenant_brand_assets SET status = 'ACTIVE' WHERE id = ?", logo))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(migrator.queryForObject("SELECT id FROM memberships WHERE id = ?", UUID.class, membership))
                .isEqualTo(membership);
    }

    @Test
    void v25EnableFenceAndV28BrandFallbackBothSurviveOrderedUpgrade() {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        var app = new JdbcTemplate(new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password"));
        assertThat(migrator.queryForList("""
                SELECT version FROM flyway_schema_history
                WHERE version IN ('25', '26', '27', '28') AND success
                ORDER BY installed_rank
                """, String.class)).containsExactly("25", "26", "27", "28");
        UUID tenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Fence tenant', 'ACTIVE') RETURNING id",
                UUID.class);
        UUID identity = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID membership = migrator.queryForObject("""
                INSERT INTO memberships (tenant_id, identity_id, membership_status)
                VALUES (?, ?, 'ENABLED') RETURNING id
                """, UUID.class, tenant, identity);
        UUID logo = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID favicon = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                VALUES (?, ?, 'LOGO', ?, 80, 32, 32), (?, ?, 'FAVICON', ?, 80, 32, 32)
                """, logo, tenant, tenant + "/" + logo + ".png", favicon, tenant,
                tenant + "/" + favicon + ".png");
        migrator.update("""
                INSERT INTO tenant_brand_profiles
                    (tenant_id, display_name, logo_url, favicon_url, primary_color, accent_color)
                VALUES (?, 'Fence brand', ?, ?, '#123456', '#234567')
                """, tenant, "/brands/" + logo, "/brands/" + favicon);
        UUID disableOperation = migrator.queryForObject("""
                INSERT INTO membership_disable_workflows
                    (operation_id, tenant_id, membership_id, actor_membership_id, actor_identity_id,
                     revocation_request_id, release_operation_id, workflow_status,
                     next_attempt_at, created_at, updated_at)
                VALUES (uuidv7(), ?, ?, ?, ?, uuidv7(), uuidv7(), 'COMPLETED', now(), now(), now())
                RETURNING operation_id
                """, UUID.class, tenant, membership, membership, identity);
        UUID enableOperation = migrator.queryForObject("""
                INSERT INTO membership_enable_workflows
                    (operation_id, tenant_id, membership_id, actor_membership_id, actor_identity_id,
                     disable_operation_id, revocation_request_id, consume_operation_id,
                     compensation_operation_id, fence_release_request_id, workflow_status,
                     next_attempt_at, created_at, updated_at)
                VALUES (uuidv7(), ?, ?, ?, ?, ?, uuidv7(), uuidv7(), uuidv7(), uuidv7(),
                        'FENCE_PENDING', now(), now(), now())
                RETURNING operation_id
                """, UUID.class, tenant, membership, membership, identity, disableOperation);
        assertThat(app.query("SELECT membership_id FROM list_accessible_membership_contexts(?)",
                (row, index) -> row.getObject(1, UUID.class), identity)).isEmpty();
        assertThat(app.query("SELECT tenant_id FROM validate_membership(?, ?)",
                (row, index) -> row.getObject(1, UUID.class), identity, membership)).isEmpty();
        migrator.update("UPDATE membership_enable_workflows SET workflow_status = 'COMPLETED' WHERE operation_id = ?",
                enableOperation);
        assertThat(app.queryForObject("SELECT brand_display_name FROM list_accessible_membership_contexts(?)",
                String.class, identity)).isEqualTo("Fence brand");
        assertThat(app.queryForObject("SELECT changed FROM disable_brand_asset(?, ?)", Boolean.class, logo,
                identity)).isTrue();
        migrator.update("UPDATE membership_enable_workflows SET workflow_status = 'FENCE_PENDING' WHERE operation_id = ?",
                enableOperation);
        assertThat(app.query("SELECT membership_id FROM list_accessible_membership_contexts(?)",
                (row, index) -> row.getObject(1, UUID.class), identity)).isEmpty();
        migrator.update("UPDATE membership_enable_workflows SET workflow_status = 'COMPLETED' WHERE operation_id = ?",
                enableOperation);
        assertThat(app.queryForMap("SELECT * FROM list_accessible_membership_contexts(?)", identity))
                .containsEntry("tenant_id", tenant)
                .containsEntry("membership_id", membership)
                .containsEntry("brand_display_name", null)
                .containsEntry("brand_logo_url", null)
                .containsEntry("brand_favicon_url", null)
                .containsEntry("brand_primary_color", null)
                .containsEntry("brand_accent_color", null);
    }

    @Test
    void exactPublicReadAndBrandProfileWritesEnforceTenantAssetsAndRevision() throws Exception {
        Flyway.configure().dataSource(url(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration").load().migrate();
        var migrator = new JdbcTemplate(new DriverManagerDataSource(
                url(), "tenant_access_migrator", "tenant-access-migrator-password"));
        UUID owner = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Owner', 'ACTIVE') RETURNING id", UUID.class);
        UUID other = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Other', 'ACTIVE') RETURNING id", UUID.class);
        UUID asset = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        var source = new DriverManagerDataSource(url(), "tenant_access_app", "tenant-access-app-password");
        var jdbc = new JdbcTemplate(source);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_brand_assets", Integer.class)).isZero();
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, owner.toString());
            jdbc.update("""
                    INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                    VALUES (?, ?, 'LOGO', ?, 80, 32, 32)
                    """, asset, owner, owner + "/" + asset + ".png");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenant_brand_assets", Integer.class)).isZero();
        assertThat(jdbc.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), asset)).containsExactly(owner + "/" + asset + ".png");
        Integer otherCount = tx.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, other.toString());
            return jdbc.queryForObject("SELECT count(*) FROM tenant_brand_assets", Integer.class);
        });
        assertThat(otherCount).isZero();
        migrator.update("UPDATE tenant_brand_assets SET status = 'DISABLED' WHERE id = ?", asset);
        assertThat(jdbc.query("SELECT object_key FROM read_public_brand_asset(?)",
                (row, index) -> row.getString(1), asset)).isEmpty();

        var profiles = new JdbcBrandProfileRepository(jdbc);
        UUID logo = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID favicon = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID foreign = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                VALUES (?, ?, 'LOGO', ?, 80, 32, 32), (?, ?, 'FAVICON', ?, 80, 32, 32),
                    (?, ?, 'LOGO', ?, 80, 32, 32)
                """, logo, owner, owner + "/" + logo + ".png",
                favicon, owner, owner + "/" + favicon + ".png",
                foreign, other, other + "/" + foreign + ".png");
        var change = new BrandProfileChange("Independent brand name", "/brands/" + logo,
                "/brands/" + favicon, "#123456", "#234567");
        long initialRevision = tx.execute(status -> profiles.read(owner).revision());
        assertThat(initialRevision).isZero();
        long savedRevision = tx.execute(status -> profiles.save(owner, 0, change).revision());
        assertThat(savedRevision).isEqualTo(1);
        String savedName = tx.execute(status -> profiles.read(owner).profile().displayName());
        assertThat(savedName).isEqualTo("Independent brand name");
        assertThatThrownBy(() -> tx.execute(status -> profiles.save(owner, 0, change)))
                .isInstanceOf(BrandProfileService.Stale.class);
        assertThatThrownBy(() -> tx.execute(status -> profiles.save(owner, 1,
                new BrandProfileChange("Foreign", "/brands/" + foreign, "/brands/" + favicon,
                        "#123456", "#234567"))))
                .isInstanceOf(BrandProfileService.AssetUnavailable.class);
        migrator.update("UPDATE tenant_brand_assets SET status = 'DISABLED' WHERE id = ?", favicon);
        assertThatThrownBy(() -> tx.execute(status -> profiles.save(owner, 1, change)))
                .isInstanceOf(BrandProfileService.AssetUnavailable.class);
        long retainedRevision = tx.execute(status -> profiles.read(owner).revision());
        assertThat(retainedRevision).isEqualTo(1);
        UUID replacement = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                VALUES (?, ?, 'FAVICON', ?, 80, 32, 32)
                """, replacement, owner, owner + "/" + replacement + ".png");
        var repaired = new BrandProfileChange("Repaired brand", "/brands/" + logo,
                "/brands/" + replacement, "#123456", "#234567");
        Long repairedRevision = tx.execute(status -> profiles.save(owner, 1, repaired).revision());
        BrandProfileChange repairedProfile = tx.execute(status -> profiles.read(owner).profile());
        assertThat(repairedRevision).isEqualTo(2);
        assertThat(repairedProfile).isEqualTo(repaired);
        migrator.update("""
                INSERT INTO tenant_brand_profiles
                    (tenant_id, display_name, logo_url, favicon_url, primary_color, accent_color)
                VALUES (?, 'Legacy brand', NULL, NULL, '#123456', '#234567')
                """, other);
        var legacy = tx.execute(status -> profiles.read(other));
        assertThat(legacy.revision()).isEqualTo(1);
        assertThat(legacy.profile().logoUrl()).isNull();
        assertThat(legacy.profile().faviconUrl()).isNull();

        UUID concurrentTenant = migrator.queryForObject(
                "INSERT INTO tenants (display_name, tenant_status) VALUES ('Concurrent', 'ACTIVE') RETURNING id", UUID.class);
        UUID concurrentLogo = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        UUID concurrentFavicon = migrator.queryForObject("SELECT uuidv7()", UUID.class);
        migrator.update("""
                INSERT INTO tenant_brand_assets (id, tenant_id, kind, object_key, byte_size, width, height)
                VALUES (?, ?, 'LOGO', ?, 80, 32, 32), (?, ?, 'FAVICON', ?, 80, 32, 32)
                """, concurrentLogo, concurrentTenant, concurrentTenant + "/" + concurrentLogo + ".png",
                concurrentFavicon, concurrentTenant, concurrentTenant + "/" + concurrentFavicon + ".png");
        var concurrentChange = new BrandProfileChange("Concurrent brand", "/brands/" + concurrentLogo,
                "/brands/" + concurrentFavicon, "#123456", "#234567");
        var started = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var writes = java.util.stream.IntStream.range(0, 2).mapToObj(index -> workers.submit(() -> {
                started.await();
                try {
                    tx.execute(status -> profiles.save(concurrentTenant, 0, concurrentChange));
                    return true;
                } catch (BrandProfileService.Stale conflict) {
                    return false;
                }
            })).toList();
            started.countDown();
            assertThat(writes.get(0).get(10, TimeUnit.SECONDS) ^ writes.get(1).get(10, TimeUnit.SECONDS)).isTrue();
            long concurrentRevision = tx.execute(status -> profiles.read(concurrentTenant).revision());
            assertThat(concurrentRevision).isEqualTo(1L);
        } finally {
            workers.shutdownNow();
        }
    }

    private static String url() {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/tenant_access_db";
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/postgresql/bootstrap.sh"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root unavailable");
    }
}
