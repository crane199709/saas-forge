package io.saas.forge.tenantaccess.application.membership;

public final class MembershipEnableFailure extends RuntimeException {
    private final String code;

    public MembershipEnableFailure(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
