package io.saasforge.platform.servicecredential.model;

public record IssuedServiceCredentialResponse(
        ServiceCredentialResponse credential, String token, boolean manifestReported) {
    @Override
    public String toString() {
        return "IssuedServiceCredentialResponse[credential=" + credential + ", token=<redacted>, manifestReported="
                + manifestReported + "]";
    }
}
