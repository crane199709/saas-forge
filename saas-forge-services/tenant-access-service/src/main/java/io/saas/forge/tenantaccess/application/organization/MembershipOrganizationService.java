package io.saas.forge.tenantaccess.application.organization;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class MembershipOrganizationService {
    private static final String MANAGE = "system:organization:manage";
    private final PermissionAuthority authority;
    private final MembershipOrganizationRepository repository;
    private final MembershipOrganizationEventFactory events;
    private final OutboxEventRepository outbox;

    public MembershipOrganizationService(PermissionAuthority authority, MembershipOrganizationRepository repository,
            MembershipOrganizationEventFactory events, OutboxEventRepository outbox) {
        this.authority = authority;
        this.repository = repository;
        this.events = events;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<UUID> list(TenantContextSnapshot context, UUID membershipId) {
        requireManage(context);
        return repository.list(context.tenantId(), valid(membershipId));
    }

    @Transactional
    public void add(TenantContextSnapshot context, UUID membershipId, UUID unitId) {
        requireManage(context);
        repository.add(context.tenantId(), valid(membershipId), valid(unitId));
        outbox.append(events.create(context, membershipId, unitId, "ADDED"));
    }

    @Transactional
    public void remove(TenantContextSnapshot context, UUID membershipId, UUID unitId) {
        requireManage(context);
        repository.remove(context.tenantId(), valid(membershipId), valid(unitId));
        outbox.append(events.create(context, membershipId, unitId, "REMOVED"));
    }

    private void requireManage(TenantContextSnapshot context) {
        if (!authority.allowed(context, MANAGE)) throw new OrganizationUnitService.Forbidden();
    }

    private UUID valid(UUID id) {
        if (id == null || id.version() != 7) throw new OrganizationUnitService.Invalid();
        return id;
    }
}
