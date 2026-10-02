package io.saas.forge.remotedelivery;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** 使用外部只读 Secret 文件取得 Remote Delivery 自己的短期 Service Access Token。 */
public final class IamServiceAccessTokenProvider {
    private static final String PLATFORM_ROLE_SCOPE = "iam:platform-role:read";
    private static final String TENANT_READ_SCOPE = "tenant-access:tenant:read";
    private static final String MEMBERSHIP_READ_SCOPE = "tenant-access:membership:read";

    private final RestClient iam;
    private final Path clientIdFile;
    private final Path clientSecretFile;
    private final Clock clock;
    private volatile CachedToken platformRoleToken;
    private volatile CachedToken tenantReadToken;
    private volatile CachedToken membershipReadToken;

    public IamServiceAccessTokenProvider(
            RestClient iam, Path clientIdFile, Path clientSecretFile, Clock clock) {
        this.iam = iam;
        this.clientIdFile = clientIdFile;
        this.clientSecretFile = clientSecretFile;
        this.clock = clock;
    }

    public synchronized String token() {
        return token(PLATFORM_ROLE_SCOPE);
    }

    public synchronized String tenantReadToken() {
        return token(TENANT_READ_SCOPE);
    }

    public synchronized String membershipReadToken() {
        return token(MEMBERSHIP_READ_SCOPE);
    }

    private String token(String scope) {
        Instant now = clock.instant();
        CachedToken cached = PLATFORM_ROLE_SCOPE.equals(scope) ? platformRoleToken
                : MEMBERSHIP_READ_SCOPE.equals(scope) ? membershipReadToken : tenantReadToken;
        if (cached != null && cached.refreshAfter().isAfter(now)) {
            return cached.value();
        }
        String clientId = readSecret(clientIdFile);
        UUID parsedClientId = UUID.fromString(clientId);
        if (parsedClientId.version() != 7 || !parsedClientId.toString().equals(clientId)) {
            throw new IllegalStateException("Remote Delivery Service Client ID 必须是规范 UUIDv7");
        }
        String clientSecret = readSecret(clientSecretFile);
        TokenResponse response = iam.post()
                .uri("/oauth2/token")
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&scope=" + encodedScope(scope))
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()
                || response.expiresIn() < 1 || !scope.equals(response.scope())) {
            throw new IllegalStateException("IAM Service Access Token 响应不合法");
        }
        CachedToken issued = new CachedToken(
                response.accessToken(), now.plusSeconds(Math.max(1, response.expiresIn() - 30L)));
        if (PLATFORM_ROLE_SCOPE.equals(scope)) {
            platformRoleToken = issued;
        } else if (MEMBERSHIP_READ_SCOPE.equals(scope)) {
            membershipReadToken = issued;
        } else {
            tenantReadToken = issued;
        }
        return issued.value();
    }

    private static String encodedScope(String scope) {
        return scope.replace(":", "%3A");
    }

    private static String readSecret(Path path) {
        try {
            String value = Files.readString(path).stripTrailing();
            if (value.isBlank()) {
                throw new IllegalStateException("Service Client Secret 文件不能为空");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("Service Client Secret 文件不可读", exception);
        }
    }

    private record CachedToken(String value, Instant refreshAfter) {
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") int expiresIn,
            String scope) {
    }
}
