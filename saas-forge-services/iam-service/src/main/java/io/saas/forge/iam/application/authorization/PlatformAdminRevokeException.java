package io.saas.forge.iam.application.authorization;

public final class PlatformAdminRevokeException extends RuntimeException {
    public enum Code { REQUEST_CONFLICT, TARGET_UNAVAILABLE, LAST_PASSWORD_ADMIN }

    private final Code code;

    public PlatformAdminRevokeException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
