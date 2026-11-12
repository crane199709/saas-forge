package io.saasforge.iam.servicecredential.model;
/** 管理端一次性交付结果；不以清单上报作为登记前提。 */
public record IssuedServiceCredentialResponse(
        ServiceCredentialResponse credential, String token, boolean manifestReported) {
    @Override
    public String toString() {
        return "IssuedServiceCredentialResponse[credential=" + credential + ", token=<redacted>, manifestReported="
                + manifestReported + "]";
    }
}
