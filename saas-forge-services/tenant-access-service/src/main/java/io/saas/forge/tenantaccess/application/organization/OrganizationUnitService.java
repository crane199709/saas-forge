package io.saas.forge.tenantaccess.application.organization;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class OrganizationUnitService {
    private static final String MANAGE = "system:organization:manage";
    private final PermissionAuthority authority;
    private final OrganizationUnitRepository repository;
    private final OrganizationUnitEventFactory events;
    private final OutboxEventRepository outbox;

    public OrganizationUnitService(PermissionAuthority authority, OrganizationUnitRepository repository,
            OrganizationUnitEventFactory events, OutboxEventRepository outbox) {
        this.authority = authority;
        this.repository = repository;
        this.events = events;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<OrganizationUnit> list(TenantContextSnapshot context) {
        requireManage(context);
        return repository.list(context.tenantId());
    }

    @Transactional
    public OrganizationUnit create(TenantContextSnapshot context, String name, UUID parentId) {
        requireManage(context);
        var result = repository.create(context.tenantId(), validName(name), parentId);
        record(context, result, "CREATED");
        return result;
    }

    @Transactional
    public OrganizationUnit rename(TenantContextSnapshot context, UUID unitId, String name) {
        requireManage(context);
        var result = repository.rename(context.tenantId(), unitId, validName(name));
        record(context, result, "RENAMED");
        return result;
    }

    @Transactional
    public OrganizationUnit move(TenantContextSnapshot context, UUID unitId, UUID parentId) {
        requireManage(context);
        var result = repository.move(context.tenantId(), unitId, parentId);
        record(context, result, "MOVED");
        return result;
    }

    @Transactional
    public void delete(TenantContextSnapshot context, UUID unitId) {
        requireManage(context);
        repository.delete(context.tenantId(), unitId);
        record(context, new OrganizationUnit(unitId, null, "", 0), "DELETED");
    }

    private void record(TenantContextSnapshot context, OrganizationUnit unit, String action) {
        outbox.append(events.create(context, unit, action));
    }

    private void requireManage(TenantContextSnapshot context) {
        if (!authority.allowed(context, MANAGE)) throw new Forbidden();
    }

    private String validName(String value) {
        if (value == null || value.isBlank() || value.length() > 200) throw new Invalid();
        return value.strip();
    }

    public static class Forbidden extends RuntimeException {}
    public static class Invalid extends RuntimeException {}
    public static class Missing extends RuntimeException {}
    public static class Conflict extends RuntimeException {}
}
