package io.saas.forge.tenantaccess.application.membership;

public final class MembershipDisableFailure extends RuntimeException {
    private final String code;

    public MembershipDisableFailure(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
