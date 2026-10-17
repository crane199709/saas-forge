package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.tenantaccess.application.organization.OrganizationUnit;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitRepository;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrganizationUnitRepository implements OrganizationUnitRepository {
    private final JdbcTemplate jdbc;
    private final UuidV7Generator ids;

    public JdbcOrganizationUnitRepository(JdbcTemplate jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override
    public List<OrganizationUnit> list(UUID tenantId) {
        target(tenantId);
        return jdbc.query("""
                SELECT unit.id, unit.parent_id, unit.name, count(member.membership_id)::integer
                FROM organization_units unit
                LEFT JOIN organization_unit_memberships member
                  ON member.tenant_id = unit.tenant_id AND member.unit_id = unit.id
                WHERE unit.tenant_id = ?
                GROUP BY unit.id, unit.parent_id, unit.name
                ORDER BY unit.name, unit.id
                """, (row, index) -> new OrganizationUnit(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), row.getString(3), row.getInt(4)), tenantId);
    }

    @Override
    public OrganizationUnit create(UUID tenantId, String name, UUID parentId) {
        lock(tenantId);
        if (parentId != null) requireUnit(tenantId, parentId);
        UUID id = ids.next();
        jdbc.update("INSERT INTO organization_units (id, tenant_id, parent_id, name) VALUES (?, ?, ?, ?)",
                id, tenantId, parentId, name);
        return new OrganizationUnit(id, parentId, name, 0);
    }

    @Override
    public OrganizationUnit rename(UUID tenantId, UUID unitId, String name) {
        lock(tenantId);
        if (unit(tenantId, unitId).name().equals(name)) throw new OrganizationUnitService.Conflict();
        jdbc.update("UPDATE organization_units SET name = ? WHERE tenant_id = ? AND id = ?", name, tenantId, unitId);
        return unit(tenantId, unitId);
    }

    @Override
    public OrganizationUnit move(UUID tenantId, UUID unitId, UUID parentId) {
        lock(tenantId);
        if (java.util.Objects.equals(unit(tenantId, unitId).parentId(), parentId))
            throw new OrganizationUnitService.Conflict();
        if (parentId != null) {
            requireUnit(tenantId, parentId);
            Boolean cycle = jdbc.queryForObject("""
                    WITH RECURSIVE descendants AS (
                        SELECT id FROM organization_units WHERE tenant_id = ? AND id = ?
                        UNION ALL
                        SELECT child.id FROM organization_units child
                        JOIN descendants ancestor ON child.parent_id = ancestor.id
                        WHERE child.tenant_id = ?
                    )
                    SELECT EXISTS (SELECT 1 FROM descendants WHERE id = ?)
                    """, Boolean.class, tenantId, unitId, tenantId, parentId);
            if (Boolean.TRUE.equals(cycle)) throw new OrganizationUnitService.Conflict();
        }
        jdbc.update("UPDATE organization_units SET parent_id = ? WHERE tenant_id = ? AND id = ?",
                parentId, tenantId, unitId);
        return unit(tenantId, unitId);
    }

    @Override
    public void delete(UUID tenantId, UUID unitId) {
        lock(tenantId);
        requireUnit(tenantId, unitId);
        Integer references = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM organization_units WHERE tenant_id = ? AND parent_id = ?)
                     + (SELECT count(*) FROM organization_unit_memberships WHERE tenant_id = ? AND unit_id = ?)
                """, Integer.class, tenantId, unitId, tenantId, unitId);
        if (references == null || references != 0) throw new OrganizationUnitService.Conflict();
        jdbc.update("DELETE FROM organization_units WHERE tenant_id = ? AND id = ?", tenantId, unitId);
    }

    private OrganizationUnit unit(UUID tenantId, UUID unitId) {
        var matches = jdbc.query("""
                SELECT unit.id, unit.parent_id, unit.name,
                       (SELECT count(*) FROM organization_unit_memberships member
                        WHERE member.tenant_id = unit.tenant_id AND member.unit_id = unit.id)::integer
                FROM organization_units unit WHERE unit.tenant_id = ? AND unit.id = ?
                """, (row, index) -> new OrganizationUnit(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), row.getString(3), row.getInt(4)), tenantId, unitId);
        if (matches.isEmpty()) throw new OrganizationUnitService.Missing();
        return matches.get(0);
    }

    private void requireUnit(UUID tenantId, UUID unitId) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM organization_units WHERE tenant_id = ? AND id = ?",
                Integer.class, tenantId, unitId);
        if (found == null || found == 0) throw new OrganizationUnitService.Missing();
    }

    private void lock(UUID tenantId) {
        target(tenantId);
        if (jdbc.query("SELECT id FROM tenants WHERE id = ? FOR UPDATE", (row, index) -> row.getObject(1, UUID.class), tenantId)
                .isEmpty()) throw new OrganizationUnitService.Missing();
    }

    private void target(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }
}
