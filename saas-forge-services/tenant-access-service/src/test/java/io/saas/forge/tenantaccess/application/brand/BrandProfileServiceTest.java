package io.saas.forge.tenantaccess.application.brand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class BrandProfileServiceTest {
    private final TenantContextSnapshot context = new TenantContextSnapshot(
            UUID.fromString("019535d9-0001-7000-8000-000000000001"),
            UUID.fromString("019535d9-0001-7000-8000-000000000002"),
            UUID.fromString("019535d9-0001-7000-8000-000000000003"));
    private final PermissionAuthority authority = mock(PermissionAuthority.class);
    private final BrandProfileRepository profiles = mock(BrandProfileRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneOffset.UTC);
    private final BrandProfileService service = new BrandProfileService(authority, profiles, outbox,
            new BrandProfileEventFactory(new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()),
                    clock, "saas.forge.dev.tenant-access-service.events"));
    private final BrandProfileChange change = new BrandProfileChange("Acme",
            "/brands/019535d9-0001-7000-8000-000000000011",
            "/brands/019535d9-0001-7000-8000-000000000012", "#123456", "#234567");

    @Test
    void savesCompleteProfileOnlyWhenRevisionAndBothAssetsAreCurrent() {
        when(authority.allowed(context, "system:brand:manage")).thenReturn(true);
        when(profiles.save(context.tenantId(), 0, change)).thenReturn(new BrandProfileRevision(1, change));
        assertEquals(1, service.save(context, 0, change).revision());
        var fact = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).append(fact.capture());
        var payload = new ObjectMapper().readTree(fact.getValue().eventSnapshot());
        assertEquals("com.saas.forge.brand.profile-saved.v1", payload.path("type").asText());
        assertEquals(1, payload.path("data").path("revision").asInt());
        assertEquals(change.logoUrl().substring(8), payload.path("data").path("logoAssetId").asText());
        assertFalse(fact.getValue().eventSnapshot().contains("Acme"));
    }

    @Test
    void rejectsUnauthorizedSaveBeforeWritingProfileOrFact() {
        assertThrows(BrandProfileService.Forbidden.class, () -> service.save(context, 0, change));
        verifyNoInteractions(profiles, outbox);
    }

    @Test
    void rejectsIncompleteProfileBeforeWritingProfileOrFact() {
        when(authority.allowed(context, "system:brand:manage")).thenReturn(true);
        assertThrows(BrandProfileService.Invalid.class, () -> service.save(context, 0,
                new BrandProfileChange("Acme", null, change.faviconUrl(), "#123456", "#234567")));
        verifyNoInteractions(profiles, outbox);
    }

    @Test
    void staleRevisionDoesNotProduceASuccessFact() {
        when(authority.allowed(context, "system:brand:manage")).thenReturn(true);
        when(profiles.save(context.tenantId(), 1, change)).thenThrow(new BrandProfileService.Stale());
        assertThrows(BrandProfileService.Stale.class, () -> service.save(context, 1, change));
        verifyNoInteractions(outbox);
    }
}
