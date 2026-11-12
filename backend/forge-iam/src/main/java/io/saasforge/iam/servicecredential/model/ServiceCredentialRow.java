package io.saasforge.iam.servicecredential.model;

import java.time.Instant;
import java.util.UUID;

/** Mapper 内部投影，禁止直接作为接口响应。 */
public record ServiceCredentialRow(
        UUID id, String service, String digest, String status, Instant createdAt, Instant lastUsedAt) {
    public ServiceCredentialResponse response() {
        return new ServiceCredentialResponse(id, service, status, digest.substring(0, 8), createdAt, lastUsedAt);
    }

    @Override
    public String toString() {
        return "ServiceCredentialRow[id=" + id + ", service=" + service + ", status=" + status + "]";
    }
}
