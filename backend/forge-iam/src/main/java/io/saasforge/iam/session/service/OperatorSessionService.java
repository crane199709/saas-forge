package io.saasforge.iam.session.service;

import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.model.OperatorCredential;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.security.service.TokenEndpointClient;
import io.saasforge.iam.session.model.OperatorTokenResponse;
import io.saasforge.sdk.core.api.ForgeException;
import java.text.ParseException;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;

/** Platform Realm 的登录与 Token 交换：流程与 Tenant Realm 相同，但账号来自 Operator 表、客户端为 platform-console。 */
@Service
public class OperatorSessionService {
    private final OperatorService operators;
    private final PasswordEncoder passwords;
    private final TokenEndpointClient tokenEndpoint;
    private final String redirectUri;
    private final boolean mfaRequired;
    private final String dummyHash;

    OperatorSessionService(
            OperatorService operators,
            PasswordEncoder passwords,
            TokenEndpointClient tokenEndpoint,
            @Value("${forge.iam.platform.redirect-uri:http://localhost:5173/}") String redirectUri,
            @Value("${forge.iam.platform.mfa-required:false}") boolean mfaRequired) {
        this.operators = operators;
        this.passwords = passwords;
        this.tokenEndpoint = tokenEndpoint;
        this.redirectUri = redirectUri;
        this.mfaRequired = mfaRequired;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public String redirectUri() {
        return redirectUri;
    }

    public Authentication authenticate(String account, String password) {
        // MFA 尚未实现：要求强制 MFA 时在校验密码之前拒绝，不能退化为仅凭密码登录，也不借此暴露密码是否正确。
        if (mfaRequired) {
            throw ForgeException.withErrorMessage(
                    SessionErrorCode.MFA_REQUIRED, "Operator MFA is required but not supported yet");
        }
        OperatorCredential credential = operators.findCredential(account);
        String hash = credential == null ? dummyHash : credential.passwordHash();
        boolean valid = passwords.matches(password, hash);
        if (!valid || credential == null) {
            throw ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Bad credentials");
        }
        // 授权端点与 Tenant Realm 共用登录会话，Token 定制器凭 OPERATOR 权限区分 Realm，拒绝跨 Realm 签发。
        var principal = User.withUsername(credential.operatorId().toString())
                .password("")
                .authorities("OPERATOR")
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    public OperatorTokenResponse code(String code, String verifier) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", verifier);
        form.add("redirect_uri", redirectUri);
        return exchange(form);
    }

    public OperatorTokenResponse refresh(String token) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", token);
        return exchange(form);
    }

    private OperatorTokenResponse exchange(LinkedMultiValueMap<String, String> form) {
        Map<?, ?> result;
        try {
            result = tokenEndpoint.grant("platform-console", form);
        } catch (HttpClientErrorException error) {
            throw ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Token grant rejected");
        }
        try {
            String access = (String) result.get("access_token");
            // 只解析本进程标准 Token 端点的响应，外部输入不会进入此路径。
            ActiveOperator operator = operators.findActive(
                    UUID.fromString(SignedJWT.parse(access).getJWTClaimsSet().getSubject()));
            if (operator == null) {
                throw ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Operator no longer active");
            }
            return new OperatorTokenResponse(
                    access,
                    (String) result.get("refresh_token"),
                    "Bearer",
                    ((Number) result.get("expires_in")).longValue(),
                    operator.displayName());
        } catch (ParseException error) {
            throw new IllegalStateException(error);
        }
    }
}
