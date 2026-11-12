package io.saasforge.iam.servicecredential.model;

/** 创建时一次性交付明文，列表与审计只返回指纹。 */
public record CreatedServiceCredentialResponse(ServiceCredentialResponse credential, String token) {
    @Override
    public String toString() {
        return "CreatedServiceCredentialResponse[credential=" + credential + ", token=<redacted>]";
    }
}
