package io.saas.forge.tenantaccess.application.invitation;

public interface InvitationMailer {
    void send(String email, String token);
}
