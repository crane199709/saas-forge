package io.saas.forge.tenantaccess.application.membership;

public final class MembershipQuotaExceededException extends RuntimeException {
    public MembershipQuotaExceededException(Throwable cause) { super("max_users quota exceeded", cause); }
}
