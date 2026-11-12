package io.saasforge.platform.servicecredential.controller;

import io.saasforge.platform.servicecredential.model.*;
import io.saasforge.platform.servicecredential.service.ServiceCredentialAdminService;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.security.RequiresPermission;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/service-credentials")
class ServiceCredentialAdminController {
    private final ServiceCredentialAdminService credentials;

    ServiceCredentialAdminController(ServiceCredentialAdminService credentials) {
        this.credentials = credentials;
    }

    @RequiresPermission("platform:service-credential:manage")
    @GetMapping
    ApiResponse<ServiceCredentialListResponse> list() {
        return ApiResponse.ok(credentials.list());
    }

    @RequiresPermission("platform:service-credential:manage")
    @PostMapping
    ApiResponse<IssuedServiceCredentialResponse> create(@RequestBody CreateRequest request) {
        return credentials.create(request.service());
    }

    @RequiresPermission("platform:service-credential:manage")
    @PostMapping("/{id}/disable")
    ApiResponse<Void> disable(@PathVariable UUID id) {
        return credentials.disable(id);
    }

    @RequiresPermission("platform:service-credential:manage")
    @PostMapping("/{id}/rotate")
    ApiResponse<IssuedServiceCredentialResponse> rotate(@PathVariable UUID id) {
        return credentials.rotate(id);
    }

    record CreateRequest(String service) {}
}
