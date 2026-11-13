package io.saasforge.platform.servicecredential.service;

import io.saasforge.platform.ops.service.OpsClient;
import io.saasforge.platform.ops.service.OpsErrorCode;
import io.saasforge.platform.servicecredential.model.*;
import io.saasforge.sdk.core.api.ApiResponse;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.security.OperatorContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

@Service
public class ServiceCredentialAdminService {
    private static final String PATH = "/service-credentials";
    private static final ParameterizedTypeReference<ApiResponse<List<ServiceCredentialResponse>>> LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<IssuedServiceCredentialResponse>> ISSUED =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<Void>> EMPTY = new ParameterizedTypeReference<>() {};
    private final OpsClient client;

    ServiceCredentialAdminService(OpsClient client) {
        this.client = client;
    }

    public ServiceCredentialListResponse list() {
        try {
            var response = client.iamGet(PATH, LIST);
            if (!ApiResponse.OK.equals(response.code()) || response.data() == null) {
                throw new ForgeException(OpsErrorCode.SERVICE_UNAVAILABLE);
            }
            return new ServiceCredentialListResponse("forge-iam", true, response.data());
        } catch (ForgeException error) {
            if (error.errorCode() != OpsErrorCode.SERVICE_UNAVAILABLE) {
                throw error;
            }
            return new ServiceCredentialListResponse("forge-iam", false, List.of());
        }
    }

    public ApiResponse<IssuedServiceCredentialResponse> create(String service) {
        // Browser request never supplies the audit actor; OperatorContext was populated by platform authentication.
        return client.iamPost(
                PATH,
                Map.of(
                        "service",
                        service == null ? "" : service,
                        "actor",
                        OperatorContext.current().operatorId()),
                ISSUED);
    }

    public ApiResponse<Void> disable(UUID id) {
        return client.iamPost(
                PATH + "/" + id + "/disable",
                Map.of("actor", OperatorContext.current().operatorId()),
                EMPTY);
    }

    public ApiResponse<IssuedServiceCredentialResponse> rotate(UUID id) {
        return client.iamPost(
                PATH + "/" + id + "/rotate",
                Map.of("actor", OperatorContext.current().operatorId()),
                ISSUED);
    }
}
