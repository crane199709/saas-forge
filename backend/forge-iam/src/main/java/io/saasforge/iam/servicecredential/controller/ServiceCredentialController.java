package io.saasforge.iam.servicecredential.controller;

import io.saasforge.iam.servicecredential.model.*;
import io.saasforge.iam.servicecredential.service.*;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 仅接受独立 IAM Ops Token；actor 由平台校验 Operator 管理权限后经专用通道提供。 */
@RestController
@RequestMapping("/internal/ops/service-credentials")
class ServiceCredentialController {
    private final ServiceCredentialService credentials;

    ServiceCredentialController(ServiceCredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping
    ApiResponse<List<ServiceCredentialResponse>> list(
            @RequestParam(required = false) String service, @RequestParam(required = false) String status) {
        if (status != null && !List.of("active", "disabled").contains(status))
            throw new ForgeException(ServiceCredentialErrorCode.INVALID_STATUS);
        return ApiResponse.ok(credentials.list(service).stream()
                .filter(row -> status == null || status.equals(row.status()))
                .toList());
    }

    @PostMapping
    ApiResponse<IssuedServiceCredentialResponse> create(@Valid @RequestBody CreateRequest request) {
        boolean reported = credentials.manifestReported(request.service());
        return ApiResponse.created(issued(credentials.create(request.service(), request.actor()), reported));
    }

    @PostMapping("/{id}/disable")
    ApiResponse<Void> disable(@PathVariable UUID id, @Valid @RequestBody ActorRequest request) {
        credentials.disable(id, request.actor());
        return ApiResponse.ok();
    }
    /** 紧急吊销并换发；常规轮换使用登记、部署、显式停旧三个步骤。 */
    @PostMapping("/{id}/rotate")
    ApiResponse<IssuedServiceCredentialResponse> rotate(
            @PathVariable UUID id, @Valid @RequestBody ActorRequest request) {
        var result = credentials.rotate(id, request.actor());
        return ApiResponse.created(
                issued(result, credentials.manifestReported(result.credential().service())));
    }

    private static IssuedServiceCredentialResponse issued(CreatedServiceCredentialResponse result, boolean reported) {
        return new IssuedServiceCredentialResponse(result.credential(), result.token(), reported);
    }

    record CreateRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z][a-z0-9-]*")
            String service,

            @NotNull UUID actor) {}

    record ActorRequest(@NotNull UUID actor) {}
}
