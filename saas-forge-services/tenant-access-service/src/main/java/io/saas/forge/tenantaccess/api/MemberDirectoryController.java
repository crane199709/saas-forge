package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/members")
public final class MemberDirectoryController {
    private static final String READ_PERMISSION = "tenant-access:membership:read";
    private final TenantContextAccessor contexts;
    private final PermissionAuthority permissions;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MemberDirectoryController(TenantContextAccessor contexts, PermissionAuthority permissions,
            JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
        this.contexts = contexts; this.permissions = permissions; this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    @GetMapping
    public ResponseEntity<Page> list() {
        var context = contexts.requireCurrent();
        if (!permissions.isAdministrator(context) && !permissions.allowed(context, READ_PERMISSION))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "成员列表权限不足");
        List<Member> items = transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    context.tenantId().toString());
            return jdbc.query("""
                    SELECT m.id, m.identity_id, m.membership_status,
                           (SELECT i.email FROM invitations i
                            WHERE i.tenant_id = m.tenant_id AND i.identity_id = m.identity_id
                              AND i.invitation_status = 'ACCEPTED'
                            ORDER BY i.updated_at DESC LIMIT 1),
                           EXISTS (SELECT 1 FROM membership_role_assignments assignment
                                   JOIN tenant_roles role ON role.tenant_id = assignment.tenant_id
                                     AND role.id = assignment.role_id
                                   WHERE assignment.tenant_id = m.tenant_id AND assignment.membership_id = m.id
                                     AND role.role_key = 'TENANT_ADMINISTRATOR' AND role.system_managed),
                           COALESCE((SELECT workflow_status FROM membership_disable_workflows workflow
                                     WHERE workflow.tenant_id = m.tenant_id AND workflow.membership_id = m.id
                                     ORDER BY workflow.created_at DESC LIMIT 1), 'NONE')
                    FROM memberships m WHERE m.tenant_id = ?
                    ORDER BY m.id LIMIT 100
                    """, (row, index) -> new Member(row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class), row.getString(3), row.getString(4),
                    row.getBoolean(5), row.getString(6)), context.tenantId());
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(items));
    }

    public record Page(List<Member> items) {}
    public record Member(UUID membershipId, UUID identityId, String status, String invitedEmail,
                         boolean administrator, String disableStatus) {}
}
