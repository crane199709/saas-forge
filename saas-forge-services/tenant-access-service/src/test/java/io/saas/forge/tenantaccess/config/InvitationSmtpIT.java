package io.saas.forge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class InvitationSmtpIT {
    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.30.7"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));

    @Test
    void deliversLatestInvitationLinkToSmtpRecipient() throws Exception {
        String token = "test-invitation-token";
        var mailer = new InvitationConfiguration().invitationMailer(
                "test", MAILPIT.getHost(), MAILPIT.getMappedPort(1025), "", "",
                "invites@saas.forge.test", false, "https://console.saas.forge.test/invitation");

        mailer.send("invitee@example.test", token);

        HttpClient client = HttpClient.newHttpClient();
        String api = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025);
        HttpResponse<String> listing = client.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/messages"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, listing.statusCode());
        assertTrue(listing.body().contains("invitee@example.test"));
        Matcher id = Pattern.compile("\\\"ID\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(listing.body());
        assertTrue(id.find());
        HttpResponse<String> message = client.send(HttpRequest.newBuilder(
                URI.create(api + "/api/v1/message/" + id.group(1))).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, message.statusCode());
        assertTrue(message.body().contains("https://console.saas.forge.test/invitation#token=" + token));
    }
}
