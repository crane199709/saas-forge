package io.saas.forge.iam.application.authentication;

public final class InvitationAcceptanceFailure extends RuntimeException {
    private final String code;
    public InvitationAcceptanceFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
