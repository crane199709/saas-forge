package io.saas.forge.example;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {
    private static final Set<String> TENANT_HEADERS = Set.of(
            "tenant", "tenantid", "tenantcontext", "tenantcontextid", "currenttenant", "currenttenantid");
    private final ProjectService projects;
    ProjectController(ProjectService projects) { this.projects = projects; }

    @PostMapping(consumes = "application/json", produces = "application/json;charset=UTF-8")
    ResponseEntity<String> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateProjectRequest request, HttpServletRequest http) {
        rejectTenantInput(http);
        if (key == null || key.isBlank()) throw new ProjectException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        var result = projects.create(uuid(key, "IDEMPOTENCY_KEY_INVALID"), request);
        var response = ResponseEntity.status(result.status()).contentType(org.springframework.http.MediaType.parseMediaType(
                result.status() < 400 ? "application/json;charset=UTF-8" : "application/problem+json;charset=UTF-8"));
        if (result.location() != null) response.location(URI.create(result.location()));
        return response.body(result.body());
    }

    @GetMapping(path = "/{projectId}", produces = "application/json;charset=UTF-8")
    ProjectResult get(@PathVariable String projectId, HttpServletRequest http) {
        rejectTenantInput(http);
        return projects.get(uuid(projectId, "VALIDATION_FAILED"));
    }

    private static UUID uuid(String value, String code) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new ProjectException(400, code, "A canonical UUIDv7 is required.");
        return UUID.fromString(value);
    }

    private static void rejectTenantInput(HttpServletRequest http) {
        if (!http.getParameterMap().isEmpty())
            throw new ProjectException(400, "VALIDATION_FAILED", "Query parameters are not supported.");
        for (String name : Collections.list(http.getHeaderNames())) {
            String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (normalized.startsWith("x")) normalized = normalized.substring(1);
            if (TENANT_HEADERS.contains(normalized))
                throw new ProjectException(400, "UNTRUSTED_CONTEXT_HEADER", "Tenant headers are not accepted.");
        }
    }
}
