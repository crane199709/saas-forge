package io.saasforge.iam.session.model;

import java.util.UUID;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String identityName,
        UUID tenantId,
        String tenantName,
        UUID membershipId) {
    @Override
    public String toString() {
        return "TokenResponse[tokenType=" + tokenType + "]";
    }
}
