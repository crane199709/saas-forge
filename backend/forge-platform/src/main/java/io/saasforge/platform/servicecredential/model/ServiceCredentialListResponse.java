package io.saasforge.platform.servicecredential.model;

import java.util.List;

public record ServiceCredentialListResponse(
        String service, boolean available, List<ServiceCredentialResponse> credentials) {}
