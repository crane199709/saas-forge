package io.saasforge.iam.session.service;

import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.lockout.model.LockoutTarget;
import io.saasforge.iam.lockout.service.LoginLockoutService;
import io.saasforge.iam.mfa.service.OperatorMfaService;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.model.OperatorCredential;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.security.config.IamProperties;
import io.saasforge.iam.security.service.LoginSessionRegistry;
import io.saasforge.iam.security.service.MfaAuthority;
import io.saasforge.iam.security.service.TokenEndpointClient;
import io.saasforge.iam.session.model.OperatorTokenResponse;
import io.saasforge.iam.session.model.PendingOperatorMfa;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.api.ForgeException;
import java.text.ParseException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Platform Realm 的登录与 Token 交换：流程与 Tenant Realm 相同，但账号来自 Operator 表、客户端为 platform-console。
 *
 * <p>密码之后的第二步见 {@link #pendingMfa}：已绑定者提交动态码或 Recovery Code；要求 Operator 使用 MFA 而未绑定者当场绑定，
 * 不需要管理员介入（账号安全规格 2.3）。
 */
@Service
public class OperatorSessionService {
    private final OperatorService operators;
    private final PasswordEncoder passwords;
    private final TokenEndpointClient tokenEndpoint;
    private final LoginAudit audits;
    private final String redirectUri;
    private final OperatorMfaService mfa;
    private final LoginLockoutService lockout;
    private final LoginSessionRegistry loginSessions;
    private final String dummyHash;

    OperatorSessionService(
            OperatorService operators,
            PasswordEncoder passwords,
            TokenEndpointClient tokenEndpoint,
            LoginAudit audits,
            OperatorMfaService mfa,
            IamProperties properties,
            LoginLockoutService lockout,
            LoginSessionRegistry loginSessions) {
        this.operators = operators;
        this.passwords = passwords;
        this.tokenEndpoint = tokenEndpoint;
        this.audits = audits;
        this.redirectUri = properties.platform().redirectUri();
        this.mfa = mfa;
        this.lockout = lockout;
        this.loginSessions = loginSessions;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public String redirectUri() {
        return redirectUri;
    }

    /** 登录成功、会话中已存入认证主体后调用，改密码时据此终止会话。 */
    public void registerLoginSession(String operatorId, String sessionId) {
        loginSessions.register(operatorId, sessionId);
    }

    // 与 Tenant Realm 相同：不存在的账号同样计数；Operator 因动态码等失败被锁定时，密码正确也被拒。
    public Authentication authenticate(String account, String password) {
        LockoutTarget target = LockoutTarget.identifier(AuditRealm.PLATFORM, account);
        lockout.check(target);
        OperatorCredential credential = operators.findCredential(account);
        String hash = credential == null ? dummyHash : credential.passwordHash();
        boolean valid = passwords.matches(password, hash);
        if (!valid || credential == null) {
            audits.failed(
                    AuditRealm.PLATFORM,
                    LoginAudit.LOGIN,
                    credential == null ? null : credential.operatorId(),
                    account,
                    SessionErrorCode.AUTH_FAILED.code());
            lockout.failed(target);
            throw ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Bad credentials");
        }
        lockout.succeeded(target);
        lockout.check(LockoutTarget.operator(credential.operatorId()));
        return authenticated(credential.operatorId(), false);
    }

    /**
     * 密码通过后的第二步：已绑定 Authenticator 时进入「待 MFA」；要求 Operator 使用 MFA 而尚未绑定时进入「必须绑定」；
     * 其余为空，直接以密码的结果建立登录会话。
     */
    public Optional<PendingOperatorMfa> pendingMfa(Authentication firstFactor) {
        UUID operatorId = UUID.fromString(firstFactor.getName());
        Instant expiresAt = Instant.now().plus(TenantSessionService.PENDING_MFA_TTL);
        if (mfa.isBound(operatorId)) {
            return Optional.of(new PendingOperatorMfa(operatorId, expiresAt, false));
        }
        if (mfa.required()) {
            return Optional.of(new PendingOperatorMfa(operatorId, expiresAt, true));
        }
        return Optional.empty();
    }

    /**
     * 以动态码或 Recovery Code（二者恰好提供一个）完成「待 MFA」，返回带 MFA 标记的会话主体；错误时状态保留，可在有效期内重试。
     *
     * @param pending 登录会话中的状态，没有时为 null
     */
    public Authentication completeMfa(PendingOperatorMfa pending, String code, String recoveryCode) {
        UUID operatorId = requirePending(pending, false);
        String method = code != null ? LoginAudit.MFA_TOTP : LoginAudit.MFA_RECOVERY_CODE;
        try {
            if (pending.expiredAt(Instant.now())) {
                throw expired();
            }
            if (code != null) {
                mfa.verifyLoginCode(operatorId, code);
            } else {
                mfa.useRecoveryCode(operatorId, recoveryCode);
            }
        } catch (ForgeException error) {
            audits.mfa(
                    AuditRealm.PLATFORM, operatorId, method, error.errorCode().code());
            throw error;
        }
        audits.mfa(AuditRealm.PLATFORM, operatorId, method, null);
        return authenticated(operatorId, true);
    }

    /** 「必须绑定」状态下开始绑定，返回供扫码的 {@code otpauth://} URI。 */
    public String startLoginBinding(PendingOperatorMfa pending) {
        UUID operatorId = requirePending(pending, true);
        if (pending.expiredAt(Instant.now())) {
            throw expired();
        }
        return mfa.startBinding(operatorId);
    }

    /**
     * 「必须绑定」状态下以首个动态码确认绑定：绑定生效即认证成功，返回带 MFA 标记的会话主体与只展示这一次的 Recovery Code。
     *
     * @param pending 登录会话中的状态，没有时为 null
     */
    public BoundLogin confirmLoginBinding(PendingOperatorMfa pending, String code) {
        UUID operatorId = requirePending(pending, true);
        if (pending.expiredAt(Instant.now())) {
            throw expired();
        }
        List<String> recoveryCodes = mfa.confirmLoginBinding(operatorId, code);
        return new BoundLogin(authenticated(operatorId, true), recoveryCodes);
    }

    /**
     * 当场绑定完成的登录。
     *
     * @param authentication 带 MFA 标记的会话主体
     * @param recoveryCodes 只展示这一次的 Recovery Code
     */
    public record BoundLogin(Authentication authentication, List<String> recoveryCodes) {
        @Override
        public String toString() {
            return "BoundLogin[redacted]";
        }
    }

    // 状态不存在或不是所需的那一步时同样按超时处理：不知道是谁在提交，不记审计，须重新输入密码。
    private static UUID requirePending(PendingOperatorMfa pending, boolean bindRequired) {
        if (pending == null || pending.bindRequired() != bindRequired) {
            throw ForgeException.withErrorMessage(SessionErrorCode.MFA_EXPIRED, "No pending operator MFA");
        }
        return pending.operatorId();
    }

    private static ForgeException expired() {
        return ForgeException.withErrorMessage(SessionErrorCode.MFA_EXPIRED, "Pending MFA expired");
    }

    private static Authentication authenticated(UUID operatorId, boolean mfaCompleted) {
        // 授权端点与 Tenant Realm 共用登录会话，Token 定制器凭 OPERATOR 权限区分 Realm，拒绝跨 Realm 签发；MFA 权限随授权保存，
        // 要求 Operator 使用 MFA 时签发与刷新都据此判断。
        var principal = User.withUsername(operatorId.toString())
                .password("")
                .authorities(
                        mfaCompleted ? new String[] {"OPERATOR", MfaAuthority.AUTHORITY} : new String[] {"OPERATOR"})
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    public OperatorTokenResponse code(String code, String verifier) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", verifier);
        form.add("redirect_uri", redirectUri);
        OperatorTokenResponse tokens = exchange(form);
        audits.operatorEntered(operatorOf(tokens));
        return tokens;
    }

    // 只解析本进程标准 Token 端点签发的 Token。
    private static UUID operatorOf(OperatorTokenResponse tokens) {
        try {
            return UUID.fromString(
                    SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet().getSubject());
        } catch (ParseException error) {
            throw new IllegalStateException(error);
        }
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
