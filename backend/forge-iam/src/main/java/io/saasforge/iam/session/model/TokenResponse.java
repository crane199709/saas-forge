package io.saasforge.iam.session.model;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String identityName,
        String tenantName) {
    @Override
    public String toString() {
        return "TokenResponse[tokenType=" + tokenType + "]";
    }
}
