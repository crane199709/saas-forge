package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantContextTest {
    static final SharedContainers.Postgres postgres = SharedContainers.postgres();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        TestTokens.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void sdkVerifiesSignatureAndExposesTenantContext() throws Exception {
        Map<?, ?> envelope = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/context")
                .header("Authorization", "Bearer " + TestTokens.forTenant(TestTokens.TENANT_ID))
                .retrieve()
                .body(Map.class);
        assertThat(envelope.get("code")).isEqualTo("ok");
        Map<?, ?> body = (Map<?, ?>) envelope.get("data");
        assertThat(body.get("identityId")).isEqualTo(TestTokens.IDENTITY_ID.toString());
        assertThat(body.get("tenantId")).isEqualTo(TestTokens.TENANT_ID.toString());
        assertThat(body.get("membershipId")).isEqualTo(TestTokens.MEMBERSHIP_ID.toString());
        assertThat(body.get("version")).isEqualTo(1);
    }

    @Test
    void sdkRejectsMissingForgedWrongRealmExpiredAndIncompleteTokens() throws Exception {
        var future = Instant.now().plusSeconds(900);
        assertThat(status(null)).isEqualTo(401);
        assertThat(status(TestTokens.token(
                        new RSAKeyGenerator(2048).generate(), TestTokens.ISSUER, future, TestTokens.TENANT_ID)))
                .isEqualTo(401);
        assertThat(status(TestTokens.token(TestTokens.KEY, "http://platform.test", future, TestTokens.TENANT_ID)))
                .isEqualTo(401);
        assertThat(status(TestTokens.token(
                        TestTokens.KEY, TestTokens.ISSUER, Instant.now().minusSeconds(120), TestTokens.TENANT_ID)))
                .isEqualTo(401);
        var original = SignedJWT.parse(TestTokens.forTenant(TestTokens.TENANT_ID));
        var incomplete = new SignedJWT(
                original.getHeader(),
                new JWTClaimsSet.Builder(original.getJWTClaimsSet())
                        .claim("membership_id", null)
                        .build());
        incomplete.sign(new RSASSASigner(TestTokens.KEY));
        assertThat(status(incomplete.serialize())).isEqualTo(401);
    }

    private int status(String token) {
        var request = RestClient.create("http://localhost:" + port).get().uri("/context");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> response.getStatusCode().value());
    }
}
