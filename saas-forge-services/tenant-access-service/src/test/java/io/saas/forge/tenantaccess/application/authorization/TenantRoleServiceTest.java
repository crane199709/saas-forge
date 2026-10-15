package io.saas.forge.tenantaccess.application.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository.Role;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class TenantRoleServiceTest {
    private static final TenantContextSnapshot CONTEXT = new TenantContextSnapshot(
            UUID.fromString("0199c200-0000-7000-8000-000000000001"),
            UUID.fromString("0199c200-0000-7000-8000-000000000002"),
            UUID.fromString("0199c200-0000-7000-8000-000000000003"));
    private static final UUID ROLE_ID = UUID.fromString("0199c200-0000-7000-8000-000000000004");
    private static final List<String> PERMISSIONS = List.of("example:project:list");

    private final PermissionAuthority authority = mock(PermissionAuthority.class);
    private final JdbcTenantRoleRepository roles = mock(JdbcTenantRoleRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final TenantRoleService service = new TenantRoleService(authority, roles, outbox,
            new UuidV7Generator(Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC),
                    new SecureRandom()),
            Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper(), "tenant.events");

    @Test
    void nonAdministratorCannotReadOrMutateTenantRoles() {
        for (Runnable operation : List.<Runnable>of(
                () -> service.list(CONTEXT), () -> service.find(CONTEXT, ROLE_ID),
                () -> service.create(CONTEXT, "PROJECT_VIEWER", PERMISSIONS),
                () -> service.update(CONTEXT, ROLE_ID, "PROJECT_VIEWER", PERMISSIONS),
                () -> service.delete(CONTEXT, ROLE_ID))) {
            assertThatThrownBy(operation::run).isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
        verifyNoInteractions(roles, outbox);
    }

    @Test
    void createUpdateAndDeleteWriteTenantScopedSuccessFacts() throws Exception {
        when(authority.isAdministrator(CONTEXT)).thenReturn(true);
        Role created = new Role(ROLE_ID, "PROJECT_VIEWER", false, PERMISSIONS, 0);
        Role updated = new Role(ROLE_ID, "PROJECT_READER", false, PERMISSIONS, 0);
        when(roles.create(CONTEXT.tenantId(), created.roleKey(), PERMISSIONS)).thenReturn(ROLE_ID);
        when(roles.find(CONTEXT.tenantId(), ROLE_ID)).thenReturn(created);
        when(roles.findLocked(CONTEXT.tenantId(), ROLE_ID)).thenReturn(created);
        when(roles.update(CONTEXT.tenantId(), ROLE_ID, updated.roleKey(), PERMISSIONS)).thenReturn(updated);

        assertThat(service.create(CONTEXT, created.roleKey(), PERMISSIONS)).isEqualTo(created);
        assertThat(service.update(CONTEXT, ROLE_ID, updated.roleKey(), PERMISSIONS)).isEqualTo(updated);
        service.delete(CONTEXT, ROLE_ID);
        verify(roles).delete(CONTEXT.tenantId(), ROLE_ID);

        var events = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, org.mockito.Mockito.times(3)).append(events.capture());
        var mapper = new ObjectMapper();
        assertThat(events.getAllValues()).extracting(event -> {
            var data = mapper.readTree(event.eventSnapshot()).get("data");
            assertThat(event.tenantId()).isEqualTo(CONTEXT.tenantId());
            assertThat(data.get("actorIdentityId").asText()).isEqualTo(CONTEXT.identityId().toString());
            assertThat(data.get("permissions").get(0).asText()).isEqualTo(PERMISSIONS.get(0));
            return data.get("action").asText();
        }).containsExactly("CREATED", "UPDATED", "DELETED");
    }

    @Test
    void unchangedRoleAndSystemRoleRejectionDoNotPublishFacts() {
        when(authority.isAdministrator(CONTEXT)).thenReturn(true);
        Role unchanged = new Role(ROLE_ID, "PROJECT_VIEWER", false, PERMISSIONS, 1);
        when(roles.findLocked(CONTEXT.tenantId(), ROLE_ID)).thenReturn(unchanged);
        assertThat(service.update(CONTEXT, ROLE_ID, unchanged.roleKey(), PERMISSIONS)).isEqualTo(unchanged);
        verifyNoInteractions(outbox);

        when(roles.findLocked(CONTEXT.tenantId(), ROLE_ID))
                .thenReturn(new Role(ROLE_ID, "TENANT_ADMINISTRATOR", true, List.of(), 1));
        assertThatThrownBy(() -> service.update(CONTEXT, ROLE_ID, "CHANGED", List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(outbox);
    }

    @Test
    void duplicateRoleKeyMapsToConflictWithoutSuccessFact() {
        when(authority.isAdministrator(CONTEXT)).thenReturn(true);
        when(roles.create(CONTEXT.tenantId(), "PROJECT_VIEWER", PERMISSIONS))
                .thenThrow(new DuplicateKeyException("role_key"));
        assertThatThrownBy(() -> service.create(CONTEXT, "PROJECT_VIEWER", PERMISSIONS))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(outbox);
    }
}
