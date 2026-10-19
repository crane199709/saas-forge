package io.saas.forge.iam.infrastructure.security;

import io.saas.forge.iam.application.authentication.ClientCredentialsTokenService;
import io.saas.forge.iam.application.authentication.TenantAccessUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 使用外部只读 Secret 文件取得 IAM 自身保留 Client 的短期 Service Access Token。 */
public class ReservedIamServiceAccessTokenProvider {
    private static final String MEMBERSHIP_READ_SCOPE = "tenant-access:membership:read";
    private static final String INVITATION_ACCEPT_SCOPE = "tenant-access:invitation:accept";

    private final ClientCredentialsTokenService tokens;
    private final Path clientIdFile;
    private final Path clientSecretFile;
    private final Clock clock;
    private volatile CachedToken membershipReadToken;
    private volatile CachedToken invitationAcceptToken;

    public ReservedIamServiceAccessTokenProvider(
            ClientCredentialsTokenService tokens,
            Path clientIdFile,
            Path clientSecretFile,
            Clock clock) {
        this.tokens = tokens;
        this.clientIdFile = clientIdFile;
        this.clientSecretFile = clientSecretFile;
        this.clock = clock;
    }

    // 保持原 HTTP 请求的事务隔离：密钥 TTL 元数据必须在签名前独立提交。
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized String membershipReadToken() {
        try {
            return issueOrReuseToken(MEMBERSHIP_READ_SCOPE);
        } catch (RuntimeException exception) {
            // 服务身份签发失败不是浏览器用户凭据错误，也不能当作成员权限被撤销。
            throw new TenantAccessUnavailableException(exception);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized String invitationAcceptToken() {
        try {
            return issueOrReuseToken(INVITATION_ACCEPT_SCOPE);
        } catch (RuntimeException unavailable) {
            throw new TenantAccessUnavailableException(unavailable);
        }
    }

    private String issueOrReuseToken(String scope) {
        Instant now = clock.instant();
        CachedToken cached = MEMBERSHIP_READ_SCOPE.equals(scope) ? membershipReadToken : invitationAcceptToken;
        if (cached != null && cached.refreshAfter().isAfter(now)) {
            return cached.value();
        }
        String clientId = readSecret(clientIdFile);
        UUID parsedClientId = UUID.fromString(clientId);
        if (parsedClientId.version() != 7 || !parsedClientId.toString().equals(clientId)) {
            throw new IllegalStateException("IAM Service Client ID 必须是规范 UUIDv7");
        }
        String clientSecret = readSecret(clientSecretFile);
        var response = tokens.issue(parsedClientId, clientSecret, "client_credentials", scope);
        if (response.value() == null || response.value().isBlank()
                || response.expiresInSeconds() < 1 || !scope.equals(response.scope())) {
            throw new IllegalStateException("IAM Service Access Token 响应不合法");
        }
        CachedToken issued = new CachedToken(
                response.value(), now.plusSeconds(Math.max(1, response.expiresInSeconds() - 30L)));
        if (MEMBERSHIP_READ_SCOPE.equals(scope)) membershipReadToken = issued;
        else invitationAcceptToken = issued;
        return issued.value();
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
}
