package io.saas.forge.tenantaccess.application.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class OrganizationUnitBehaviorTest {
    private static final TenantContextSnapshot CONTEXT = new TenantContextSnapshot(
            UUID.fromString("0199c200-0000-7000-8000-000000000001"),
            UUID.fromString("0199c200-0000-7000-8000-000000000002"),
            UUID.fromString("0199c200-0000-7000-8000-000000000003"));
    private static final UUID UNIT = UUID.fromString("0199c200-0000-7000-8000-000000000004");
    private static final UUID PARENT = UUID.fromString("0199c200-0000-7000-8000-000000000005");

    private final PermissionAuthority authority = mock(PermissionAuthority.class);
    private final OrganizationUnitRepository repository = mock(OrganizationUnitRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);
    private final OrganizationUnitService service = new OrganizationUnitService(authority, repository,
            new OrganizationUnitEventFactory(new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()),
                    clock, "tenant.events"), outbox);

    @Test
    void createTrimsNameAndRecordsUnitChange() throws Exception {
        when(authority.allowed(CONTEXT, "system:organization:manage")).thenReturn(true);
        var created = new OrganizationUnit(UNIT, PARENT, "Engineering", 0);
        when(repository.create(CONTEXT.tenantId(), "Engineering", PARENT)).thenReturn(created);

        assertThat(service.create(CONTEXT, "  Engineering  ", PARENT)).isEqualTo(created);
        verify(repository).create(CONTEXT.tenantId(), "Engineering", PARENT);
        assertEvent("CREATED", true);
    }

    @Test
    void renameMoveAndDeleteRecordDistinctFacts() throws Exception {
        when(authority.allowed(CONTEXT, "system:organization:manage")).thenReturn(true);
        when(repository.rename(CONTEXT.tenantId(), UNIT, "Operations"))
                .thenReturn(new OrganizationUnit(UNIT, PARENT, "Operations", 2));
        when(repository.move(CONTEXT.tenantId(), UNIT, null))
                .thenReturn(new OrganizationUnit(UNIT, null, "Operations", 2));

        assertThat(service.rename(CONTEXT, UNIT, " Operations ").memberCount()).isEqualTo(2);
        assertThat(service.move(CONTEXT, UNIT, null).parentId()).isNull();
        service.delete(CONTEXT, UNIT);
        verify(repository).delete(CONTEXT.tenantId(), UNIT);

        var events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, org.mockito.Mockito.times(3)).append(events.capture());
        assertThat(events.getAllValues()).extracting(OutboxEvent::eventSnapshot)
                .allSatisfy(snapshot -> assertThat(snapshot).contains(CONTEXT.tenantId().toString(), UNIT.toString()))
                .anySatisfy(snapshot -> assertThat(snapshot).contains("RENAMED", "parentId"))
                .anySatisfy(snapshot -> assertThat(snapshot).contains("MOVED"))
                .anySatisfy(snapshot -> assertThat(snapshot).contains("DELETED").doesNotContain("parentId"));
    }

    @Test
    void invalidNamesAndDeniedPermissionLeaveRepositoryAndOutboxUntouched() {
        when(authority.allowed(CONTEXT, "system:organization:manage")).thenReturn(true);
        for (String name : List.of("", " ", "x".repeat(201))) {
            assertThatThrownBy(() -> service.create(CONTEXT, name, null))
                    .isInstanceOf(OrganizationUnitService.Invalid.class);
        }
        assertThatThrownBy(() -> service.rename(CONTEXT, UNIT, null))
                .isInstanceOf(OrganizationUnitService.Invalid.class);
        when(authority.allowed(CONTEXT, "system:organization:manage")).thenReturn(false);
        assertThatThrownBy(() -> service.delete(CONTEXT, UNIT))
                .isInstanceOf(OrganizationUnitService.Forbidden.class);
        org.mockito.Mockito.verifyNoInteractions(repository, outbox);
    }

    private void assertEvent(String action, boolean hasParent) throws Exception {
        var event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).append(event.capture());
        var fact = event.getValue();
        var envelope = new ObjectMapper().readTree(fact.eventSnapshot());
        assertThat(fact.tenantId()).isEqualTo(CONTEXT.tenantId());
        assertThat(envelope.get("type").asText()).isEqualTo(OrganizationUnitEventFactory.TYPE);
        assertThat(envelope.get("data").get("action").asText()).isEqualTo(action);
        assertThat(envelope.get("data").has("parentId")).isEqualTo(hasParent);
    }
}
