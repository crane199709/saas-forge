package io.saas.forge.tenantaccess.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Tenant Role 的持久权威；调用方须在同一事务中执行管理授权与成功事实写入。 */
@Repository
public class JdbcTenantRoleRepository {
    private final JdbcTemplate jdbc;

    public JdbcTenantRoleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Role(UUID id, String roleKey, boolean systemManaged, List<String> permissions, int assignedMembers) {}

    public List<Role> list(UUID tenantId) {
        target(tenantId);
        return jdbc.query("""
                SELECT id, role_key, system_managed,
                    (SELECT count(*) FROM membership_role_assignments a WHERE a.role_id = r.id) AS assigned_members
                FROM tenant_roles r WHERE tenant_id = ? ORDER BY role_key
                """, (row, number) -> role(row.getObject(1, UUID.class), row.getString(2),
                    row.getBoolean(3), row.getInt(4)), tenantId);
    }

    public Role find(UUID tenantId, UUID roleId) {
        target(tenantId);
        List<Role> rows = jdbc.query("""
                SELECT id, role_key, system_managed,
                    (SELECT count(*) FROM membership_role_assignments a WHERE a.role_id = r.id) AS assigned_members
                FROM tenant_roles r WHERE tenant_id = ? AND id = ?
                """, (row, number) -> role(row.getObject(1, UUID.class), row.getString(2),
                    row.getBoolean(3), row.getInt(4)), tenantId, roleId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
        return rows.get(0);
    }

    public Role findLocked(UUID tenantId, UUID roleId) {
        target(tenantId);
        List<UUID> found = jdbc.query("SELECT id FROM tenant_roles WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (row, number) -> row.getObject(1, UUID.class), tenantId, roleId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
        return find(tenantId, roleId);
    }

    public UUID create(UUID tenantId, String roleKey, List<String> permissions) {
        target(tenantId);
        validate(roleKey, permissions);
        validatePermissions(permissions);
        UUID id = jdbc.queryForObject("SELECT uuidv7()", UUID.class);
        jdbc.update("INSERT INTO tenant_roles (id, tenant_id, role_key, system_managed, created_at) VALUES (?, ?, ?, false, now())",
                id, tenantId, roleKey);
        replacePermissions(tenantId, id, permissions);
        return id;
    }

    public Role update(UUID tenantId, UUID roleId, String roleKey, List<String> permissions) {
        target(tenantId);
        validate(roleKey, permissions);
        lockCustomRole(tenantId, roleId);
        validatePermissions(permissions);
        jdbc.update("UPDATE tenant_roles SET role_key = ? WHERE tenant_id = ? AND id = ?", roleKey, tenantId, roleId);
        replacePermissions(tenantId, roleId, permissions);
        return find(tenantId, roleId);
    }

    public void delete(UUID tenantId, UUID roleId) {
        target(tenantId);
        lockCustomRole(tenantId, roleId);
        Integer assigned = jdbc.queryForObject("SELECT count(*) FROM membership_role_assignments WHERE tenant_id = ? AND role_id = ?",
                Integer.class, tenantId, roleId);
        if (assigned != null && assigned > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "ROLE_ASSIGNED");
        jdbc.update("DELETE FROM tenant_role_permissions WHERE tenant_id = ? AND role_id = ?", tenantId, roleId);
        jdbc.update("DELETE FROM tenant_roles WHERE tenant_id = ? AND id = ?", tenantId, roleId);
    }

    private Role role(UUID id, String key, boolean systemManaged, int count) {
        List<String> permissions = jdbc.query("""
                SELECT permission_code FROM tenant_role_permissions WHERE role_id = ? ORDER BY permission_code
                """, (row, number) -> row.getString(1), id);
        return new Role(id, key, systemManaged, permissions, count);
    }

    private void lockCustomRole(UUID tenantId, UUID roleId) {
        List<Boolean> rows = jdbc.query("SELECT system_managed FROM tenant_roles WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (row, number) -> row.getBoolean(1), tenantId, roleId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
        if (rows.get(0)) throw new ResponseStatusException(HttpStatus.CONFLICT, "SYSTEM_ROLE_IMMUTABLE");
    }

    private void validatePermissions(List<String> permissions) {
        for (String code : permissions) {
            if (code.startsWith("system:") && !"system:organization:manage".equals(code)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "ADMIN_PERMISSION_RESERVED");
            }
            Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM permission_catalog WHERE code = ?)",
                    Boolean.class, code);
            if (!Boolean.TRUE.equals(exists)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PERMISSION_NOT_FOUND");
            }
        }
    }

    private void replacePermissions(UUID tenantId, UUID roleId, List<String> permissions) {
        jdbc.update("DELETE FROM tenant_role_permissions WHERE tenant_id = ? AND role_id = ?", tenantId, roleId);
        for (String code : permissions) {
            jdbc.update("INSERT INTO tenant_role_permissions (tenant_id, role_id, permission_code, assigned_at) VALUES (?, ?, ?, now())",
                    tenantId, roleId, code);
        }
    }

    private void validate(String roleKey, List<String> permissions) {
        if (roleKey == null || !roleKey.matches("^[A-Z][A-Z0-9_]{2,63}$")
                || "TENANT_ADMINISTRATOR".equals(roleKey) || permissions == null
                || permissions.size() > 100 || permissions.stream().anyMatch(code -> code == null || code.length() > 160)
                || permissions.stream().distinct().count() != permissions.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        }
    }

    private void target(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
    }
}
