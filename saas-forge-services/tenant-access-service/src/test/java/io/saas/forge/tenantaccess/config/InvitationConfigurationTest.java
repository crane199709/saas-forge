package io.saas.forge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InvitationConfigurationTest {
    @Test
    void disabledProductionSmtpFailsDeliveryWithoutBlockingStartup() {
        var mailer = new InvitationConfiguration().invitationMailer(
                "prod", "saas.forge.test", "", 1025, "", "", "", false, "");
        assertThrows(io.saas.forge.tenantaccess.application.invitation.InvitationDeliveryException.class,
                () -> mailer.send("recipient@example.test", "token"));
    }

    @Test
    void rejectsInvitationLinkOnAnUncontrolledHost() {
        for (String page : java.util.List.of("https://attacker.example/invitation",
                "https://console.saas.forge.test.attacker.example/invitation",
                "https://user@console.saas.forge.test/invitation",
                "https://console.saas.forge.test:8443/invitation")) {
            assertThrows(IllegalStateException.class, () -> new InvitationConfiguration().invitationMailer(
                    "test", "saas.forge.test", "127.0.0.1", 1025, "", "", "invites@saas.forge.test", false,
                    page));
        }
    }
}
