package io.saas.forge.tenantaccess.application.invitation;

public final class InvitationDeliveryException extends RuntimeException {
    public InvitationDeliveryException() {
        super("Invitation 邮件投递不可用");
    }

    public InvitationDeliveryException(Throwable cause) {
        super("Invitation 邮件投递不可用", cause);
    }
}
