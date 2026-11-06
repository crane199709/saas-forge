package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.mfa.service.Totp;
import io.saasforge.iam.provisioning.mapper.OutboxAuditMapper;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditOutcome;
import io.saasforge.sdk.audit.AuditRealm;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Operator MFA 集成测试共用的 HTTP 调用：登录会话、授权码换 Token、Platform Access Token 调用与 TOTP 计算。 */
final class PlatformMfaClient {
    static final String PASSWORD = "Operator-password-123!";
    static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final OutboxAuditMapper outbox;

    PlatformMfaClient(int port, OutboxAuditMapper outbox) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.outbox = outbox;
    }

    record Response(int status, Map<?, ?> body) {
        String code() {
            return (String) body.get("code");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data() {
            return (Map<String, Object>) body.get("data");
        }

        @SuppressWarnings("unchecked")
        List<String> recoveryCodes() {
            return (List<String>) data().get("recoveryCodes");
        }
    }

    /** 一次请求的响应与之后应使用的会话 Cookie。 */
    record Login(Response response, String cookie) {}

    Login login(String account) {
        return post("/platform/auth/login", Map.of("account", account, "password", PASSWORD), null, null);
    }

    /** 带会话 Cookie 或 Bearer Token 发送 JSON 请求；响应换发会话 Cookie 时返回新值，否则沿用原值。 */
    Login post(String path, Object body, String cookie, String bearer) {
        return client().post()
                .uri(path)
                .headers(headers -> {
                    if (cookie != null) headers.add("Cookie", cookie);
                    if (bearer != null) headers.setBearerAuth(bearer);
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? Map.of() : body)
                .exchange((req, res) -> {
                    assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
                    String issued = res.getHeaders().getFirst("Set-Cookie");
                    return new Login(
                            new Response(res.getStatusCode().value(), res.bodyTo(Map.class)),
                            issued == null ? cookie : issued.split(";")[0]);
                });
    }

    Response withToken(String bearer, String path, Object body) {
        return post(path, body, null, bearer).response();
    }

    /** 已认证会话经授权码流程换取的 Platform Token。 */
    Map<?, ?> tokens(String cookie) {
        Response exchanged = post(
                        "/platform/auth/exchange",
                        Map.of("code", authorizationCode(cookie), "codeVerifier", VERIFIER),
                        null,
                        null)
                .response();
        assertThat(exchanged.status()).isEqualTo(200);
        return exchanged.data();
    }

    String accessToken(String cookie) {
        return (String) tokens(cookie).get("accessToken");
    }

    int authorizeStatus(String cookie) {
        return authorizeRequest(cookie)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    String authorizationCode(String cookie) {
        var response = authorizeRequest(cookie).retrieve().toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        return UriComponentsBuilder.fromUri(response.getHeaders().getLocation())
                .build()
                .getQueryParams()
                .getFirst("code");
    }

    private RestClient.RequestHeadersSpec<?> authorizeRequest(String cookie) {
        try {
            String challenge = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
            return client().get()
                    .uri(builder -> builder.path("/oauth2/authorize")
                            .queryParam("response_type", "code")
                            .queryParam("client_id", "platform-console")
                            .queryParam("redirect_uri", "http://localhost:5173/")
                            .queryParam("scope", "platform")
                            .queryParam("state", "test-state")
                            .queryParam("code_challenge_method", "S256")
                            .queryParam("code_challenge", challenge)
                            .build())
                    .header("Cookie", cookie);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** 该 Operator 的审计动作，按发生顺序；auth.mfa 记为「auth.mfa:方式:success|错误码」，其余为动作名。 */
    List<String> audits(UUID operatorId) {
        return outbox.payloadsOf(AuditEvent.class.getSimpleName()).stream()
                .map(payload -> JSON.readValue(payload, AuditEvent.class))
                .filter(event -> operatorId.equals(event.actorId())
                        && (event.action().startsWith("mfa.") || event.action().equals("auth.mfa")))
                .peek(event -> {
                    assertThat(event.realm()).isEqualTo(AuditRealm.PLATFORM);
                    assertThat(event.tenantId()).isNull();
                })
                .map(event -> event.action().equals("auth.mfa")
                        ? "auth.mfa:" + event.detail().get("method") + ":"
                                + (event.outcome() == AuditOutcome.SUCCESS ? "success" : event.errorCode())
                        : event.action())
                .toList();
    }

    static byte[] secretOf(String otpauthUri) {
        String encoded = UriComponentsBuilder.fromUriString(otpauthUri)
                .build()
                .getQueryParams()
                .getFirst("secret");
        var out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : encoded.toCharArray()) {
            buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    static String code(byte[] secret, int offset) {
        return Totp.code(secret, Totp.step(Instant.now()) + offset);
    }

    /** 与容差窗口内任何一步都不同的码。 */
    static String wrongCode(byte[] secret) {
        var valid = List.of(code(secret, -1), code(secret, 0), code(secret, 1), code(secret, 2));
        for (int candidate = 0; ; candidate++) {
            String code = "%06d".formatted(candidate);
            if (!valid.contains(code)) return code;
        }
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()))
                .build();
    }
}
