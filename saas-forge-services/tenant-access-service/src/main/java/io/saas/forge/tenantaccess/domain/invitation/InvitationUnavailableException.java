package io.saas.forge.tenantaccess.domain.invitation;

public final class InvitationUnavailableException extends IllegalStateException {
    public InvitationUnavailableException() {
        super("Invitation 已失效或不可修改");
    }
}
