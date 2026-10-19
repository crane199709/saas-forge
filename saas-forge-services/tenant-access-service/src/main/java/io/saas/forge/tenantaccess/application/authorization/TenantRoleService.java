package io.saas.forge.tenantaccess.application.authorization;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcTenantRoleRepository.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TenantRoleService {
    private static final String SCHEMA = "https://saas.forge.io/contracts/events/tenant-role-changed.v1.schema.json";
    private final PermissionAuthority authority;
    private final JdbcTenantRoleRepository roles;
    private final OutboxEventRepository outbox;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final ObjectMapper json;
    private final String topic;

    public TenantRoleService(PermissionAuthority authority, JdbcTenantRoleRepository roles,
            OutboxEventRepository outbox, UuidV7Generator ids, Clock clock, ObjectMapper json,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        this.authority = authority;
        this.roles = roles;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
        this.topic = topic;
    }

    @Transactional(readOnly = true)
    public List<Role> list(TenantContextSnapshot context) {
        requireAdministrator(context);
        return roles.list(context.tenantId());
    }

    @Transactional(readOnly = true)
    public Role find(TenantContextSnapshot context, UUID roleId) {
        requireAdministrator(context);
        return roles.find(context.tenantId(), roleId);
    }

    @Transactional
    public Role create(TenantContextSnapshot context, String roleKey, List<String> permissions) {
        requireAdministrator(context);
        try {
            UUID roleId = roles.create(context.tenantId(), roleKey, permissions);
            Role created = roles.find(context.tenantId(), roleId);
            append(context, created, "CREATED");
            return created;
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROLE_KEY_EXISTS", conflict);
        }
    }

    @Transactional
    public Role update(TenantContextSnapshot context, UUID roleId, String roleKey, List<String> permissions) {
        requireAdministrator(context);
        Role previous = roles.findLocked(context.tenantId(), roleId);
        if (previous.systemManaged()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SYSTEM_ROLE_IMMUTABLE");
        }
        if (previous.roleKey().equals(roleKey) && previous.permissions().equals(permissions)) return previous;
        try {
            Role updated = roles.update(context.tenantId(), roleId, roleKey, permissions);
            append(context, updated, "UPDATED");
            return updated;
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROLE_KEY_EXISTS", conflict);
        }
    }

    @Transactional
    public void delete(TenantContextSnapshot context, UUID roleId) {
        requireAdministrator(context);
        Role previous = roles.findLocked(context.tenantId(), roleId);
        roles.delete(context.tenantId(), roleId);
        append(context, previous, "DELETED");
    }

    private void requireAdministrator(TenantContextSnapshot context) {
        if (!authority.isAdministrator(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_ADMINISTRATOR_REQUIRED");
        }
    }

    private void append(TenantContextSnapshot context, Role role, String action) {
        UUID eventId = ids.next();
        Instant at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", context.tenantId().toString());
        data.put("actorIdentityId", context.identityId().toString());
        data.put("roleId", role.id().toString());
        data.put("roleKey", role.roleKey());
        data.put("action", action);
        data.put("permissions", role.permissions());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "urn:saas.forge:tenant-access-service");
        envelope.put("type", "com.saas.forge.tenant-role.changed.v1");
        envelope.put("subject", context.tenantId().toString());
        envelope.put("time", at.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", SCHEMA);
        envelope.put("data", data);
        outbox.append(new OutboxEvent(eventId, context.tenantId(), at, topic,
                context.tenantId().toString(), null, json.writeValueAsString(envelope)));
    }
}
