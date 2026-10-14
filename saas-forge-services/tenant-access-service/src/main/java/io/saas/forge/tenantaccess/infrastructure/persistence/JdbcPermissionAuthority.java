package io.saas.forge.tenantaccess.infrastructure.persistence;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPermissionAuthority implements PermissionAuthority {
    private final JdbcTemplate jdbc;

    public JdbcPermissionAuthority(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allowed(TenantContextSnapshot context, String code) {
        operationTarget(context);
        Boolean answer = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM permission_catalog permission
                    JOIN memberships member ON member.id = ? AND member.tenant_id = ?
                    JOIN tenants tenant ON tenant.id = member.tenant_id
                    WHERE permission.code = ? AND permission.enabled
                      AND member.identity_id = ? AND member.membership_status = 'ENABLED'
                      AND tenant.tenant_status = 'ACTIVE'
                      AND NOT EXISTS (
                          SELECT 1 FROM tenant_lifecycle_workflows closure
                          WHERE closure.tenant_id = tenant.id AND closure.lifecycle_action = 'CLOSE'
                            AND closure.workflow_status IN ('PENDING', 'RECOVERY_REQUIRED'))
                      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
                      AND (
                          EXISTS (
                              SELECT 1 FROM membership_role_assignments assignment
                              JOIN tenant_roles role ON role.id = assignment.role_id
                              WHERE assignment.membership_id = member.id
                                AND assignment.tenant_id = member.tenant_id
                                AND role.role_key = 'TENANT_ADMINISTRATOR' AND role.system_managed
                          )
                          OR EXISTS (
                              SELECT 1 FROM membership_role_assignments assignment
                              JOIN tenant_role_permissions grant_entry
                                ON grant_entry.role_id = assignment.role_id
                               AND grant_entry.tenant_id = assignment.tenant_id
                              WHERE assignment.membership_id = member.id
                                AND assignment.tenant_id = member.tenant_id
                                AND grant_entry.permission_code = permission.code
                          )
                      )
                )
                """, Boolean.class, context.membershipId(), context.tenantId(), code, context.identityId());
        return Boolean.TRUE.equals(answer);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAdministrator(TenantContextSnapshot context) {
        operationTarget(context);
        Boolean answer = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM memberships member
                    JOIN tenants tenant ON tenant.id = member.tenant_id
                    JOIN membership_role_assignments assignment
                      ON assignment.membership_id = member.id AND assignment.tenant_id = member.tenant_id
                    JOIN tenant_roles role ON role.id = assignment.role_id AND role.tenant_id = assignment.tenant_id
                    WHERE member.id = ? AND member.tenant_id = ? AND member.identity_id = ?
                      AND member.membership_status = 'ENABLED'
                      AND tenant.tenant_status = 'ACTIVE'
                      AND NOT EXISTS (
                          SELECT 1 FROM tenant_lifecycle_workflows closure
                          WHERE closure.tenant_id = tenant.id AND closure.lifecycle_action = 'CLOSE'
                            AND closure.workflow_status IN ('PENDING', 'RECOVERY_REQUIRED'))
                      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
                      AND role.role_key = 'TENANT_ADMINISTRATOR' AND role.system_managed
                )
                """, Boolean.class, context.membershipId(), context.tenantId(), context.identityId());
        return Boolean.TRUE.equals(answer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionEntry> catalog() {
        return jdbc.query("SELECT code, enabled FROM permission_catalog ORDER BY code",
                (row, index) -> new PermissionEntry(row.getString(1), row.getBoolean(2)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> effective(TenantContextSnapshot context) {
        operationTarget(context);
        return jdbc.query("""
                SELECT permission.code FROM permission_catalog permission
                JOIN memberships member ON member.id = ? AND member.tenant_id = ?
                JOIN tenants tenant ON tenant.id = member.tenant_id
                WHERE permission.enabled AND member.identity_id = ?
                  AND member.membership_status = 'ENABLED' AND tenant.tenant_status = 'ACTIVE'
                  AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
                  AND (
                    EXISTS (
                      SELECT 1 FROM membership_role_assignments assignment
                      JOIN tenant_roles role ON role.id = assignment.role_id
                      WHERE assignment.membership_id = member.id AND assignment.tenant_id = member.tenant_id
                        AND role.role_key = 'TENANT_ADMINISTRATOR' AND role.system_managed
                    )
                    OR EXISTS (
                      SELECT 1 FROM membership_role_assignments assignment
                      JOIN tenant_role_permissions grant_entry ON grant_entry.role_id = assignment.role_id
                        AND grant_entry.tenant_id = assignment.tenant_id
                      WHERE assignment.membership_id = member.id AND assignment.tenant_id = member.tenant_id
                        AND grant_entry.permission_code = permission.code
                    )
                  )
                ORDER BY permission.code
                """, (row, index) -> row.getString(1),
                context.membershipId(), context.tenantId(), context.identityId());
    }

    private void operationTarget(TenantContextSnapshot context) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                context.tenantId().toString());
    }
}
