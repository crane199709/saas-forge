package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.organization.MembershipOrganizationRepository;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMembershipOrganizationRepository implements MembershipOrganizationRepository {
    private final JdbcTemplate jdbc;

    public JdbcMembershipOrganizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> list(UUID tenantId, UUID membershipId) {
        target(tenantId);
        requireMembership(tenantId, membershipId);
        return jdbc.query("""
                SELECT unit_id FROM organization_unit_memberships
                WHERE tenant_id = ? AND membership_id = ? ORDER BY unit_id
                """, (row, index) -> row.getObject(1, UUID.class), tenantId, membershipId);
    }

    @Override
    public void add(UUID tenantId, UUID membershipId, UUID unitId) {
        lockTenant(tenantId);
        requireMembership(tenantId, membershipId);
        requireUnit(tenantId, unitId);
        int inserted = jdbc.update("""
                INSERT INTO organization_unit_memberships (tenant_id, membership_id, unit_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, tenantId, membershipId, unitId);
        if (inserted == 0) throw new OrganizationUnitService.Conflict();
    }

    @Override
    public void remove(UUID tenantId, UUID membershipId, UUID unitId) {
        lockTenant(tenantId);
        requireMembership(tenantId, membershipId);
        int removed = jdbc.update("""
                DELETE FROM organization_unit_memberships
                WHERE tenant_id = ? AND membership_id = ? AND unit_id = ?
                """, tenantId, membershipId, unitId);
        if (removed == 0) throw new OrganizationUnitService.Missing();
    }

    private void requireMembership(UUID tenantId, UUID membershipId) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM memberships WHERE tenant_id = ? AND id = ?", Integer.class,
                tenantId, membershipId);
        if (found == null || found == 0) throw new OrganizationUnitService.Missing();
    }

    private void requireUnit(UUID tenantId, UUID unitId) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM organization_units WHERE tenant_id = ? AND id = ?", Integer.class,
                tenantId, unitId);
        if (found == null || found == 0) throw new OrganizationUnitService.Missing();
    }

    private void lockTenant(UUID tenantId) {
        target(tenantId);
        if (jdbc.query("SELECT id FROM tenants WHERE id = ? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), tenantId).isEmpty()) {
            throw new OrganizationUnitService.Missing();
        }
    }

    private void target(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }
}
