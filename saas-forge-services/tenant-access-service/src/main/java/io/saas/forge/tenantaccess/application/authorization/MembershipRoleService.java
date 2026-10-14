package io.saas.forge.tenantaccess.application.authorization;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEvent;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcMembershipRoleRepository;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcMembershipRoleRepository.Assignment;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcMembershipRoleRepository.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MembershipRoleService {
    private static final String SCHEMA = "https://saas.forge.io/contracts/events/membership-role-changed.v1.schema.json";
    private final PermissionAuthority authority;
    private final JdbcMembershipRoleRepository assignments;
    private final OutboxEventRepository outbox;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final ObjectMapper json;
    private final String topic;

    public MembershipRoleService(PermissionAuthority authority, JdbcMembershipRoleRepository assignments,
            OutboxEventRepository outbox, UuidV7Generator ids, Clock clock, ObjectMapper json,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        this.authority = authority;
        this.assignments = assignments;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
        this.topic = topic;
    }

    @Transactional(readOnly = true)
    public List<Assignment> list(TenantContextSnapshot context, UUID membershipId) {
        requireAdministrator(context);
        return assignments.list(context.tenantId(), membershipId);
    }

    @Transactional
    public List<Assignment> assign(TenantContextSnapshot context, UUID membershipId, UUID roleId) {
        assignments.lockTenant(context.tenantId());
        requireAdministrator(context);
        Role role = assignments.role(context.tenantId(), roleId);
        if (assignments.assign(context, membershipId, roleId)) append(context, membershipId, role, "ASSIGNED");
        return assignments.list(context.tenantId(), membershipId);
    }

    @Transactional
    public List<Assignment> revoke(TenantContextSnapshot context, UUID membershipId, UUID roleId) {
        assignments.lockTenant(context.tenantId());
        requireAdministrator(context);
        Role role = assignments.role(context.tenantId(), roleId);
        if (assignments.revoke(context, membershipId, role)) append(context, membershipId, role, "REVOKED");
        return assignments.list(context.tenantId(), membershipId);
    }

    private void requireAdministrator(TenantContextSnapshot context) {
        if (!authority.isAdministrator(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_ADMINISTRATOR_REQUIRED");
        }
    }

    private void append(TenantContextSnapshot context, UUID membershipId, Role role, String action) {
        UUID eventId = ids.next();
        Instant at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", context.tenantId().toString());
        data.put("actorIdentityId", context.identityId().toString());
        data.put("membershipId", membershipId.toString());
        data.put("roleId", role.id().toString());
        data.put("roleKey", role.key());
        data.put("action", action);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId.toString());
        envelope.put("source", "urn:saas.forge:tenant-access-service");
        envelope.put("type", "com.saas.forge.membership-role.changed.v1");
        envelope.put("subject", context.tenantId().toString());
        envelope.put("time", at.toString());
        envelope.put("datacontenttype", "application/json");
        envelope.put("dataschema", SCHEMA);
        envelope.put("data", data);
        outbox.append(new OutboxEvent(eventId, context.tenantId(), at, topic,
                context.tenantId().toString(), null, json.writeValueAsString(envelope)));
    }
}
