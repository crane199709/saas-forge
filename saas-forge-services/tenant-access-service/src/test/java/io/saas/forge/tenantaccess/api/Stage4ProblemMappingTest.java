package io.saas.forge.tenantaccess.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.saas.forge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saas.forge.tenantaccess.application.invitation.InvitationFailure;
import io.saas.forge.tenantaccess.application.membership.MembershipDisableFailure;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableFailure;
import io.saas.forge.tenantaccess.application.tenant.TenantLifecycleException;
import io.saas.forge.tenantaccess.domain.invitation.InvitationUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class Stage4ProblemMappingTest {
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    @Test
    void roleAndMembershipRejectionsRemainCompleteProblemDetailsThroughGateway() {
        var handler = new AccessControlProblemHandler();
        var request = request("/api/v1/tenant-roles");
        assertProblem(handler.responseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND"),
                request), HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
        assertProblem(handler.responseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND"),
                request), HttpStatus.NOT_FOUND, "MEMBERSHIP_NOT_FOUND");
        assertProblem(handler.responseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "TENANT_ADMINISTRATOR_REQUIRED"), request), HttpStatus.FORBIDDEN, "TENANT_ADMINISTRATOR_REQUIRED");
        assertProblem(handler.responseStatus(new ResponseStatusException(HttpStatus.CONFLICT,
                "LAST_TENANT_ADMINISTRATOR"), request), HttpStatus.CONFLICT, "LAST_TENANT_ADMINISTRATOR");
        var unsafe = handler.responseStatus(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "raw member email should stay private"), request);
        assertProblem(unsafe, HttpStatus.FORBIDDEN, "TENANT_ACCESS_REQUEST_FAILED");
        assertThat(unsafe.toString()).doesNotContain("raw member email");
    }

    @Test
    void organizationErrorsDistinguishBusinessConflictFromStorageOutage() {
        var handler = new OrganizationUnitExceptionHandler();
        var organization = request("/api/v1/organization-units");
        assertProblem(handler.forbidden(organization), HttpStatus.FORBIDDEN, "ORGANIZATION_PERMISSION_DENIED");
        assertProblem(handler.invalid(organization), HttpStatus.BAD_REQUEST, "ORGANIZATION_UNIT_INVALID");
        assertProblem(handler.missing(organization), HttpStatus.NOT_FOUND, "ORGANIZATION_UNIT_NOT_FOUND");
        assertProblem(handler.conflict(organization), HttpStatus.CONFLICT, "ORGANIZATION_UNIT_CONFLICT");
        assertProblem(handler.concurrentReference(organization), HttpStatus.CONFLICT, "ORGANIZATION_UNIT_CONFLICT");
        assertProblem(handler.unavailable(organization), HttpStatus.SERVICE_UNAVAILABLE, "ORGANIZATION_UNAVAILABLE");
        var other = request("/api/v1/members");
        assertProblem(handler.concurrentReference(other), HttpStatus.INTERNAL_SERVER_ERROR, "TENANT_ACCESS_UNAVAILABLE");
        assertProblem(handler.unavailable(other), HttpStatus.INTERNAL_SERVER_ERROR, "TENANT_ACCESS_UNAVAILABLE");
    }

    @Test
    void invitationProblemsPreserveStableCodesAndNeverExposeDependencyDetails() {
        var handler = new InvitationExceptionHandler();
        var request = request("/api/v1/invitations");
        assertThat(handler.invitation(new InvitationFailure("INVITATION_NOT_FOUND"), request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.invitation(new InvitationFailure("TENANT_NOT_ACCESSIBLE"), request).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        var conflict = handler.invitation(new InvitationFailure("INVITATION_ALREADY_PENDING"), request);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().code()).isEqualTo("INVITATION_ALREADY_PENDING");
        assertThat(conflict.getBody().detail()).isEqualTo("INVITATION_ALREADY_PENDING");
        assertThat(conflict.getBody().traceId()).isEqualTo(TRACE);
        assertThat(handler.unavailable(new InvitationUnavailableException(), request).getBody().code())
                .isEqualTo("INVITATION_NOT_PENDING");
        assertThat(handler.invalid(new IllegalArgumentException("raw email"), request).getBody().code())
                .isEqualTo("INVITATION_EMAIL_INVALID");
        var outage = handler.dependencyUnavailable(
                new RemoteWorkflowUnavailableException(new IllegalStateException("secret endpoint")), request);
        assertThat(outage.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(outage.getBody().code()).isEqualTo("INVITATION_DEPENDENCY_UNAVAILABLE");
        assertThat(outage.getBody().toString()).doesNotContain("secret endpoint");
    }

    @Test
    void membershipWorkflowErrorsMapToClientActionableHttpStatus() {
        var request = request("/api/v1/members");
        var disable = new MembershipDisableExceptionHandler();
        var enable = new MembershipEnableExceptionHandler();
        for (String code : new String[] {"MEMBERSHIP_DISABLE_FORBIDDEN", "MEMBERSHIP_ADMINISTRATOR_PROTECTED"}) {
            assertThat(disable.failure(new MembershipDisableFailure(code), request).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(disable.failure(new MembershipDisableFailure("MEMBERSHIP_NOT_FOUND"), request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(disable.failure(new MembershipDisableFailure("INVALID_UUID_V7"), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(disable.failure(new MembershipDisableFailure("WORKFLOW_PENDING"), request).getBody().traceId())
                .isEqualTo(TRACE);
        assertThat(disable.failure(new MembershipDisableFailure("WORKFLOW_PENDING"), request).getBody().detail())
                .isEqualTo("WORKFLOW_PENDING");
        assertThat(enable.failure(new MembershipEnableFailure("MEMBERSHIP_ENABLE_FORBIDDEN"), request).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(enable.failure(new MembershipEnableFailure("TENANT_NOT_FOUND"), request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(enable.failure(new MembershipEnableFailure("INVALID_UUID_V7"), request).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(enable.failure(new MembershipEnableFailure("MAX_USERS_EXCEEDED"), request).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(enable.failure(new MembershipEnableFailure("MAX_USERS_EXCEEDED"), request).getBody().detail())
                .isEqualTo("MAX_USERS_EXCEEDED");
    }

    @Test
    void pendingTenantLifecycleHasRetryHeaderButFinalConflictDoesNot() {
        var handler = new TenantCreationExceptionHandler();
        var request = request("/api/v1/platform/tenants");
        var pending = handler.tenantLifecycleFailure(
                new TenantLifecycleException("TENANT_CLOSURE_PENDING", "resume later", 7), request);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(pending.getHeaders().getFirst("Retry-After")).isEqualTo("7");
        assertThat(pending.getBody().traceId()).isEqualTo(TRACE);
        assertThat(handler.tenantLifecycleFailure(
                new TenantLifecycleException("TENANT_NOT_FOUND", "missing"), request).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        var conflict = handler.tenantLifecycleFailure(
                new TenantLifecycleException("TENANT_CLOSED", "closed"), request);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getHeaders().getFirst("Retry-After")).isNull();
    }

    private static MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("traceparent", "00-" + TRACE + "-0123456789abcdef-01");
        return request;
    }

    private static void assertProblem(org.springframework.http.ProblemDetail actual, HttpStatus status, String code) {
        assertThat(actual.getStatus()).isEqualTo(status.value());
        assertThat(actual.getProperties()).containsEntry("code", code);
        assertThat(actual.getProperties()).containsEntry("traceId", TRACE);
        assertThat(actual.getType().toString()).isEqualTo("urn:saas.forge:problem:" + code.toLowerCase().replace('_', '-'));
        assertThat(actual.getTitle()).isNotBlank();
        assertThat(actual.getDetail()).isNotBlank();
    }
}
