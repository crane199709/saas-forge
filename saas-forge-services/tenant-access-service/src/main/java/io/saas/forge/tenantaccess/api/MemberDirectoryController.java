package io.saas.forge.tenantaccess.api;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.contract.api.MembersApi;
import io.saas.forge.tenantaccess.contract.model.AddMembershipOrganizationUnitRequest;
import io.saas.forge.tenantaccess.contract.model.MemberPage;
import io.saas.forge.tenantaccess.contract.model.MemberRoleAssignment;
import io.saas.forge.tenantaccess.contract.model.MemberRoleAssignments;
import io.saas.forge.tenantaccess.contract.model.MembershipDisableProgress;
import io.saas.forge.tenantaccess.contract.model.MembershipEnableProgress;
import io.saas.forge.tenantaccess.contract.model.MembershipOrganizationUnitList;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MemberDirectoryController implements MembersApi {
    private static final String READ_PERMISSION = "tenant-access:membership:read";
    private final TenantContextAccessor contexts;
    private final PermissionAuthority permissions;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MembershipDisableController disables;
    private final MembershipEnableController enables;
    private final MembershipOrganizationController organizations;
    private final MembershipRoleController roles;

    public MemberDirectoryController(TenantContextAccessor contexts, PermissionAuthority permissions,
            JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
            MembershipDisableController disables, MembershipEnableController enables,
            MembershipOrganizationController organizations, MembershipRoleController roles) {
        this.contexts = contexts; this.permissions = permissions; this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.disables = disables;
        this.enables = enables;
        this.organizations = organizations;
        this.roles = roles;
    }

    @Override
    public ResponseEntity<MemberPage> listMembers() {
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
                                     ORDER BY workflow.created_at DESC LIMIT 1), 'NONE'),
                           COALESCE((SELECT workflow_status FROM membership_enable_workflows workflow
                                     WHERE workflow.tenant_id = m.tenant_id AND workflow.membership_id = m.id
                                     ORDER BY workflow.created_at DESC LIMIT 1), 'NONE')
                    FROM memberships m WHERE m.tenant_id = ?
                    ORDER BY m.id LIMIT 100
                    """, (row, index) -> new Member(row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class), row.getString(3), row.getString(4),
                    row.getBoolean(5), row.getString(6), row.getString(7)), context.tenantId());
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new MemberPage(items.stream().map(item ->
                        new io.saas.forge.tenantaccess.contract.model.Member(item.membershipId(), item.identityId(),
                                io.saas.forge.tenantaccess.contract.model.Member.StatusEnum.valueOf(item.status()),
                                item.administrator(),
                                io.saas.forge.tenantaccess.contract.model.Member.DisableStatusEnum.valueOf(item.disableStatus()),
                                io.saas.forge.tenantaccess.contract.model.Member.EnableStatusEnum.valueOf(item.enableStatus()))
                                .invitedEmail(item.invitedEmail())).toList()));
    }

    @Override
    public ResponseEntity<MembershipDisableProgress> disableMembership(UUID idempotencyKey, UUID membershipId) {
        return disableResponse(disables.disable(membershipId, idempotencyKey, currentRequest()));
    }

    @Override
    public ResponseEntity<MembershipDisableProgress> readMembershipDisable(UUID membershipId) {
        return disableResponse(disables.read(membershipId));
    }

    @Override
    public ResponseEntity<MembershipDisableProgress> recoverMembershipDisable(UUID membershipId) {
        return disableResponse(disables.recover(membershipId, currentRequest()));
    }

    @Override
    public ResponseEntity<MembershipEnableProgress> enableMembership(UUID idempotencyKey, UUID membershipId) {
        return enableResponse(enables.enable(membershipId, idempotencyKey, currentRequest()));
    }

    @Override
    public ResponseEntity<MembershipEnableProgress> readMembershipEnable(UUID membershipId) {
        return enableResponse(enables.read(membershipId));
    }

    @Override
    public ResponseEntity<MembershipOrganizationUnitList> listMembershipOrganizationUnits(UUID membershipId) {
        return noStore(new MembershipOrganizationUnitList(
                new LinkedHashSet<>(organizations.list(membershipId).getBody().unitIds())));
    }

    @Override
    public ResponseEntity<Void> addMembershipOrganizationUnit(UUID membershipId, AddMembershipOrganizationUnitRequest body) {
        return organizations.add(membershipId,
                new MembershipOrganizationController.AddUnit(body == null ? null : body.getUnitId()));
    }

    @Override
    public ResponseEntity<Void> removeMembershipOrganizationUnit(UUID membershipId, UUID unitId) {
        return organizations.remove(membershipId, unitId);
    }

    @Override
    public ResponseEntity<MemberRoleAssignments> listMembershipRoles(UUID membershipId) {
        return roleResponse(roles.list(membershipId));
    }

    @Override
    public ResponseEntity<MemberRoleAssignments> assignMembershipRole(UUID membershipId, UUID roleId) {
        return roleResponse(roles.assign(membershipId, roleId));
    }

    @Override
    public ResponseEntity<MemberRoleAssignments> revokeMembershipRole(UUID membershipId, UUID roleId) {
        return roleResponse(roles.revoke(membershipId, roleId));
    }

    private static ResponseEntity<MembershipDisableProgress> disableResponse(
            ResponseEntity<io.saas.forge.tenantaccess.application.membership.MembershipDisableService.Progress> source) {
        var value = source.getBody();
        return ResponseEntity.status(source.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(new MembershipDisableProgress(
                        MembershipDisableProgress.OperationStatusEnum.valueOf(value.operationStatus()),
                        MembershipDisableProgress.MembershipStatusEnum.valueOf(value.membershipStatus()),
                        value.pending(), value.recoveryRequired()).operationId(value.operationId()));
    }

    private static ResponseEntity<MembershipEnableProgress> enableResponse(
            ResponseEntity<io.saas.forge.tenantaccess.application.membership.MembershipEnableService.Progress> source) {
        var value = source.getBody();
        return ResponseEntity.status(source.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(new MembershipEnableProgress(
                        MembershipEnableProgress.OperationStatusEnum.valueOf(value.operationStatus()),
                        MembershipEnableProgress.MembershipStatusEnum.valueOf(value.membershipStatus()),
                        value.pending()).operationId(value.operationId()));
    }

    private static ResponseEntity<MemberRoleAssignments> roleResponse(
            ResponseEntity<MembershipRoleController.RoleAssignments> source) {
        return noStore(new MemberRoleAssignments(source.getBody().items().stream()
                .map(item -> new MemberRoleAssignment(item.roleId(), item.roleKey(), item.systemManaged())).toList()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }

    public record Member(UUID membershipId, UUID identityId, String status, String invitedEmail,
                         boolean administrator, String disableStatus, String enableStatus) {}
}
