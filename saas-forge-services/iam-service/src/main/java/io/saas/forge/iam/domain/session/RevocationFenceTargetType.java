package io.saas.forge.iam.domain.session;

public enum RevocationFenceTargetType {
    MEMBERSHIP("membership"),
    TENANT("tenant"),
    IDENTITY("identity");

    private final String keySegment;

    RevocationFenceTargetType(String keySegment) {
        this.keySegment = keySegment;
    }

    public String keySegment() {
        return keySegment;
    }
}
