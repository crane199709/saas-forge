package io.saas.forge.tenantaccess.api;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Keep role and member denials on the Gateway's public Problem Details contract. */
@RestControllerAdvice(assignableTypes = {TenantRoleController.class, MemberDirectoryController.class})
public class AccessControlProblemHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException failure, HttpServletRequest request) {
        String reason = failure.getReason();
        String code = reason != null && reason.matches("[A-Z][A-Z0-9_]{2,100}")
                ? reason : "TENANT_ACCESS_REQUEST_FAILED";
        var problem = ProblemDetail.forStatusAndDetail(failure.getStatusCode(), code);
        problem.setType(URI.create("urn:saas.forge:problem:"
                + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle("Tenant access request failed");
        problem.setProperty("code", code);
        String traceId = TenantCreationController.traceId(request);
        problem.setProperty("traceId", traceId == null ? UUID.randomUUID().toString().replace("-", "") : traceId);
        return problem;
    }
}
