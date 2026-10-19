package io.saas.forge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
                "https://console.saas.forge.test:8443/invitation",
                "http://console.saas.forge.test/invitation",
                "https://console.saas.forge.test/invitation?redirect=https://attacker.example",
                "https://console.saas.forge.test/invitation#token=attacker")) {
            assertThrows(IllegalStateException.class, () -> new InvitationConfiguration().invitationMailer(
                    "test", "saas.forge.test", "127.0.0.1", 1025, "", "", "invites@saas.forge.test", false,
                    page));
        }
    }

    @Test
    void productionSenderRequiresCompleteCredentialsAndStartTlsBeforeItCanSendLinks() {
        String page = "https://console.saas.forge.test/invitation";
        for (String[] missing : new String[][] {
                {"", "password", "invites@saas.forge.test", "true", page},
                {"user", "", "invites@saas.forge.test", "true", page},
                {"user", "password", "", "true", page},
                {"user", "password", "invites@saas.forge.test", "false", page},
                {"user", "password", "invites@saas.forge.test", "true", ""}
        }) {
            assertThrows(IllegalStateException.class, () -> new InvitationConfiguration().invitationMailer(
                    "prod", "saas.forge.test", "127.0.0.1", 1025,
                    missing[0], missing[1], missing[2], Boolean.parseBoolean(missing[3]), missing[4]));
        }
        assertDoesNotThrow(() -> new InvitationConfiguration().invitationMailer(
                "prod", "saas.forge.test", "127.0.0.1", 1025,
                "user", "password", "invites@saas.forge.test", true, page));
    }

    @Test
    void configuredSenderRejectsMalformedDeploymentDomainAndSmtpPort() {
        for (String rootDomain : java.util.List.of("Example.COM", "bad..example", "-bad.example", "example.")) {
            assertThrows(IllegalStateException.class, () -> new InvitationConfiguration().invitationMailer(
                    "dev", rootDomain, "127.0.0.1", 1025, "", "", "invites@example.test", false, ""));
        }
        for (int port : new int[] {0, 65536}) {
            assertThrows(IllegalStateException.class, () -> new InvitationConfiguration().invitationMailer(
                    "dev", "saas.forge.test", "127.0.0.1", port, "", "", "invites@example.test", false, ""));
        }
    }
}
