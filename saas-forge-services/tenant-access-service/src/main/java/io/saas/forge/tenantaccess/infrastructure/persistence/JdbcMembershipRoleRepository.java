package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class JdbcMembershipRoleRepository {
    private final JdbcTemplate jdbc;

    public JdbcMembershipRoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Assignment(UUID roleId, String roleKey, boolean systemManaged) {}
    public record Role(UUID id, String key, boolean administrator) {}

    /** 所有管理员数量变更先锁 Tenant；禁用 Membership 的工作流也须使用同一把锁。 */
    public void lockTenant(UUID tenantId) {
        target(tenantId);
        List<UUID> rows = jdbc.query("SELECT id FROM tenants WHERE id = ? FOR UPDATE",
                (row, index) -> row.getObject(1, UUID.class), tenantId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND");
    }

    public List<Assignment> list(UUID tenantId, UUID membershipId) {
        requireMember(tenantId, membershipId);
        return jdbc.query("""
                SELECT role.id, role.role_key, role.system_managed
                FROM membership_role_assignments assignment
                JOIN tenant_roles role ON role.id = assignment.role_id AND role.tenant_id = assignment.tenant_id
                WHERE assignment.tenant_id = ? AND assignment.membership_id = ?
                ORDER BY role.role_key
                """, (row, index) -> new Assignment(row.getObject(1, UUID.class), row.getString(2),
                row.getBoolean(3)), tenantId, membershipId);
    }

    public Role role(UUID tenantId, UUID roleId) {
        List<Role> rows = jdbc.query("""
                SELECT id, role_key, system_managed FROM tenant_roles
                WHERE tenant_id = ? AND id = ? FOR KEY SHARE
                """, (row, index) -> new Role(row.getObject(1, UUID.class), row.getString(2),
                row.getBoolean(3) && "TENANT_ADMINISTRATOR".equals(row.getString(2))), tenantId, roleId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
        return rows.get(0);
    }

    public boolean assign(TenantContextSnapshot context, UUID membershipId, UUID roleId) {
        requireMember(context.tenantId(), membershipId);
        role(context.tenantId(), roleId);
        return jdbc.update("""
                INSERT INTO membership_role_assignments (tenant_id, membership_id, role_id, assigned_at)
                VALUES (?, ?, ?, now()) ON CONFLICT DO NOTHING
                """, context.tenantId(), membershipId, roleId) == 1;
    }

    public boolean revoke(TenantContextSnapshot context, UUID membershipId, Role role) {
        String status = requireMember(context.tenantId(), membershipId);
        Boolean assigned = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM membership_role_assignments
                WHERE tenant_id = ? AND membership_id = ? AND role_id = ?)
                """, Boolean.class, context.tenantId(), membershipId, role.id());
        if (!Boolean.TRUE.equals(assigned)) return false;
        if (role.administrator() && "ENABLED".equals(status) && enabledAdministrators(context.tenantId()) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LAST_TENANT_ADMINISTRATOR");
        }
        return jdbc.update("""
                DELETE FROM membership_role_assignments
                WHERE tenant_id = ? AND membership_id = ? AND role_id = ?
                """, context.tenantId(), membershipId, role.id()) == 1;
    }

    public int enabledAdministrators(UUID tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM memberships member
                JOIN membership_role_assignments assignment
                  ON assignment.membership_id = member.id AND assignment.tenant_id = member.tenant_id
                JOIN tenant_roles role ON role.id = assignment.role_id AND role.tenant_id = assignment.tenant_id
                WHERE member.tenant_id = ? AND member.membership_status = 'ENABLED'
                  AND role.role_key = 'TENANT_ADMINISTRATOR' AND role.system_managed
                """, Integer.class, tenantId);
        return count == null ? 0 : count;
    }

    private String requireMember(UUID tenantId, UUID membershipId) {
        target(tenantId);
        List<String> rows = jdbc.query("SELECT membership_status FROM memberships WHERE tenant_id = ? AND id = ?",
                (row, index) -> row.getString(1), tenantId, membershipId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND");
        return rows.get(0);
    }

    private void target(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }
}
