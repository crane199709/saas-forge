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
        return writeResponse(result);
    }

    static ResponseEntity<String> writeResponse(ProjectWriteResult result) {
        if (result.status() == 204) return ResponseEntity.noContent().build();
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

    @GetMapping(produces = "application/json;charset=UTF-8")
    ProjectPage list(HttpServletRequest http) {
        rejectTenantInput(http, Set.of("cursor", "limit"));
        String rawLimit = http.getParameter("limit");
        int limit = 50;
        if (rawLimit != null) {
            if (!rawLimit.matches("[1-9][0-9]{0,2}")) throw ProjectException.invalidPage();
            limit = Integer.parseInt(rawLimit);
            if (limit > 100) throw ProjectException.invalidPage();
        }
        return projects.list(http.getParameter("cursor"), limit);
    }

    @PutMapping(path = "/{projectId}", consumes = "application/json", produces = "application/json;charset=UTF-8")
    ResponseEntity<String> update(@PathVariable String projectId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "If-Match", required = false) String version,
            @Valid @RequestBody CreateProjectRequest request, HttpServletRequest http) {
        rejectTenantInput(http);
        if (key == null || key.isBlank()) throw new ProjectException(400, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required.");
        long expected = version(version);
        return writeResponse(projects.update(uuid(projectId, "VALIDATION_FAILED"), uuid(key, "IDEMPOTENCY_KEY_INVALID"), expected, request, ProjectExceptionHandler.traceId(http)));
    }

    static long version(String version) {
        if (version == null) throw new ProjectException(428, "VERSION_REQUIRED", "If-Match is required.");
        if (!version.matches("\"[1-9][0-9]{0,18}\""))
            throw new ProjectException(400, "VALIDATION_FAILED", "A quoted positive version is required.");

        try { return Long.parseLong(version.substring(1, version.length() - 1)); }
        catch (NumberFormatException invalid) { throw new ProjectException(400, "VALIDATION_FAILED", "The version is invalid."); }
    }

    static UUID uuid(String value, String code) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
            throw new ProjectException(400, code, "A canonical UUIDv7 is required.");
        return UUID.fromString(value);
    }

    static void rejectTenantInput(HttpServletRequest http) {
        rejectTenantInput(http, Set.of());
    }

    static void rejectTenantInput(HttpServletRequest http, Set<String> allowed) {
        for (var parameter : http.getParameterMap().entrySet())
            if (!allowed.contains(parameter.getKey()) || parameter.getValue().length != 1) throw ProjectException.invalidPage();
        for (String name : Collections.list(http.getHeaderNames())) {
            String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (normalized.startsWith("x")) normalized = normalized.substring(1);
            if (TENANT_HEADERS.contains(normalized))
                throw new ProjectException(400, "UNTRUSTED_CONTEXT_HEADER", "Tenant headers are not accepted.");
        }
    }
}
