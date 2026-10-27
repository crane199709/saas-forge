package io.saasforge.iam.session.model;

public record OperatorTokenResponse(
        String accessToken, String refreshToken, String tokenType, long expiresIn, String operatorName) {
    @Override
    public String toString() {
        return "OperatorTokenResponse[tokenType=" + tokenType + "]";
    }
}
