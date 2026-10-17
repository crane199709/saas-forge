package io.saas.forge.tenantaccess.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.membership.MembershipDisableService;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableService;
import io.saas.forge.tenantaccess.contract.model.AddMembershipOrganizationUnitRequest;
import io.saas.forge.tenantaccess.infrastructure.persistence.JdbcMembershipRoleRepository.Assignment;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

class MemberDirectoryControllerBehaviorTest {
    private static final TenantContextSnapshot CONTEXT = new TenantContextSnapshot(
            UUID.fromString("0199c200-0000-7000-8000-000000000001"),
            UUID.fromString("0199c200-0000-7000-8000-000000000002"),
            UUID.fromString("0199c200-0000-7000-8000-000000000003"));
    private static final UUID MEMBER = UUID.fromString("0199c200-0000-7000-8000-000000000004");
    private static final UUID ROLE = UUID.fromString("0199c200-0000-7000-8000-000000000005");
    private static final UUID UNIT = UUID.fromString("0199c200-0000-7000-8000-000000000006");
    private static final UUID OPERATION = UUID.fromString("0199c200-0000-7000-8000-000000000007");

    private final TenantContextAccessor contexts = mock(TenantContextAccessor.class);
    private final PermissionAuthority authority = mock(PermissionAuthority.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final MembershipDisableController disables = mock(MembershipDisableController.class);
    private final MembershipEnableController enables = mock(MembershipEnableController.class);
    private final MembershipOrganizationController organizations = mock(MembershipOrganizationController.class);
    private final MembershipRoleController roles = mock(MembershipRoleController.class);
    private final MemberDirectoryController controller = new MemberDirectoryController(contexts, authority,
            jdbc, manager, disables, enables, organizations, roles);

    @BeforeEach
    void currentRequest() {
        when(contexts.requireCurrent()).thenReturn(CONTEXT);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void memberReadRequiresCurrentPermissionAndReturnsTenantScopedStatus() {
        assertThatThrownBy(controller::listMembers).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(jdbc);

        when(authority.allowed(CONTEXT, "tenant-access:membership:read")).thenReturn(true);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(CONTEXT.tenantId().toString())))
                .thenReturn(CONTEXT.tenantId().toString());
        org.mockito.Mockito.doReturn(List.of(new MemberDirectoryController.Member(MEMBER, CONTEXT.identityId(),
                "ENABLED", "guest@example.com", false, "NONE", "NONE")))
                .when(jdbc).query(anyString(), any(RowMapper.class), eq(CONTEXT.tenantId()));

        var response = controller.listMembers();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().getItems()).singleElement().satisfies(member -> {
            assertThat(member.getMembershipId()).isEqualTo(MEMBER);
            assertThat(member.getIdentityId()).isEqualTo(CONTEXT.identityId());
            assertThat(member.getInvitedEmail()).isEqualTo("guest@example.com");
            assertThat(member.getStatus().getValue()).isEqualTo("ENABLED");
            assertThat(member.getAdministrator()).isFalse();
        });
        verify(jdbc).queryForObject(anyString(), eq(String.class), eq(CONTEXT.tenantId().toString()));
    }

    @Test
    void workflowRoutesKeepPendingAndRecoverySemantics() {
        var disable = new MembershipDisableService.Progress(OPERATION, "REVOKING", "ENABLED", true, true);
        var enable = new MembershipEnableService.Progress(OPERATION, "COMPLETED", "ENABLED", false);
        when(disables.disable(eq(MEMBER), eq(OPERATION), any())).thenReturn(ResponseEntity.accepted().body(disable));
        when(disables.read(MEMBER)).thenReturn(ResponseEntity.ok(disable));
        when(disables.recover(eq(MEMBER), any())).thenReturn(ResponseEntity.ok(disable));
        when(enables.enable(eq(MEMBER), eq(OPERATION), any())).thenReturn(ResponseEntity.ok(enable));
        when(enables.read(MEMBER)).thenReturn(ResponseEntity.ok(enable));

        var pending = controller.disableMembership(OPERATION, MEMBER);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(pending.getBody().getRecoveryRequired()).isTrue();
        assertThat(pending.getBody().getOperationId()).isEqualTo(OPERATION);
        assertThat(controller.readMembershipDisable(MEMBER).getBody().getPending()).isTrue();
        assertThat(controller.recoverMembershipDisable(MEMBER).getBody().getOperationStatus().getValue())
                .isEqualTo("REVOKING");
        assertThat(controller.enableMembership(OPERATION, MEMBER).getBody().getMembershipStatus().getValue())
                .isEqualTo("ENABLED");
        assertThat(controller.readMembershipEnable(MEMBER).getBody().getPending()).isFalse();
    }

    @Test
    void organizationAndRoleRoutesReturnOnlyCurrentAssignments() {
        when(organizations.list(MEMBER)).thenReturn(ResponseEntity.ok(
                new MembershipOrganizationController.UnitList(List.of(UNIT))));
        when(organizations.add(eq(MEMBER), any())).thenReturn(ResponseEntity.status(201).build());
        when(organizations.remove(MEMBER, UNIT)).thenReturn(ResponseEntity.noContent().build());
        var assignment = new Assignment(ROLE, "PROJECT_VIEWER", false);
        var assignments = new MembershipRoleController.RoleAssignments(List.of(assignment));
        when(roles.list(MEMBER)).thenReturn(ResponseEntity.ok(assignments));
        when(roles.assign(MEMBER, ROLE)).thenReturn(ResponseEntity.ok(assignments));
        when(roles.revoke(MEMBER, ROLE)).thenReturn(ResponseEntity.ok(assignments));

        assertThat(controller.listMembershipOrganizationUnits(MEMBER).getBody().getUnitIds())
                .containsExactly(UNIT);
        assertThat(controller.addMembershipOrganizationUnit(MEMBER, new AddMembershipOrganizationUnitRequest(UNIT))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.removeMembershipOrganizationUnit(MEMBER, UNIT).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        for (var response : List.of(controller.listMembershipRoles(MEMBER),
                controller.assignMembershipRole(MEMBER, ROLE), controller.revokeMembershipRole(MEMBER, ROLE))) {
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody().getItems()).singleElement().satisfies(role -> {
                assertThat(role.getRoleId()).isEqualTo(ROLE);
                assertThat(role.getRoleKey()).isEqualTo("PROJECT_VIEWER");
            });
        }
    }
}
