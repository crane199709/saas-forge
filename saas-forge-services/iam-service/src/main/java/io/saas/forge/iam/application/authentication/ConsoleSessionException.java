package io.saas.forge.iam.application.authentication;

public final class ConsoleSessionException extends RuntimeException {
    public enum Code {
        SESSION_INVALID, SESSION_ALREADY_ACTIVE, SESSION_COOKIE_MISMATCH,
        SESSION_REVISION_CHANGED, SESSION_REVISION_REQUIRED, SESSION_TRANSITION_PENDING,
        SESSION_SECURITY_UNAVAILABLE, INITIAL_CREDENTIAL_RESTRICTED, CONTEXTS_UNAVAILABLE,
        CURRENT_CONTEXT_REVOKED, TARGET_CONTEXT_UNAVAILABLE, CONTEXT_REFRESH_REQUIRED,
        IDEMPOTENCY_KEY_REUSED, REFRESH_CONTEXT_CHANGED, VALIDATION_FAILED
    }
    private final Code code;
    public ConsoleSessionException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
