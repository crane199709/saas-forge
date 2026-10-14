package io.saas.forge.tenantaccess.application.invitation;

public final class InvitationFailure extends RuntimeException {
    private final String code;

    public InvitationFailure(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
