package io.saasforge.iam.password.service;

import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.IdentityPassword;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.lockout.model.LockoutTarget;
import io.saasforge.iam.lockout.service.LoginLockoutService;
import io.saasforge.iam.membership.service.MembershipStateService;
import io.saasforge.iam.mfa.service.IdentityMfaService;
import io.saasforge.iam.operator.model.OperatorPassword;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.operator.service.OperatorStateService;
import io.saasforge.iam.password.model.ResetTicket;
import io.saasforge.iam.security.service.LoginSessionRegistry;
import io.saasforge.iam.security.service.RedisAuthorizationService;
import io.saasforge.iam.session.service.LoginAudit;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.iam.verification.service.VerificationCodeService;
import io.saasforge.iam.verification.service.VerificationErrorCode;
import io.saasforge.iam.verification.service.VerificationPurpose;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.api.ForgeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Service;

/**
 * 自助改密码（Identity 与 Operator，需原密码）与 Identity 忘记密码（验证码 + 已绑定时 MFA）。
 *
 * <p>成功后使该账号的其他凭证失效（账号安全规格第 5 节）：
 *
 * <ul>
 *   <li>已签发的 Access Token：递增版本，网关比对版本后拒绝。Operator 沿用管理员重置密码的机制，递增 Operator 版本；Identity 没有自己的版本，
 *       递增其全部有效 Membership 的版本。
 *   <li>Refresh Token：删除该账号的全部授权；自助改密码保留发起请求的这一授权，客户端刷新后按新版本继续使用。
 *   <li>iam 登录会话：全部终止，不能再借授权码流程签发新 Token。
 * </ul>
 *
 * <p>原密码错误、验证码错误与动态码错误计入 Login Lockout，锁定期内这些入口一并拒绝：原密码计在 Identity 或 Operator 上，验证码计在规范化后的
 * 邮箱或手机号上（与登录共用计数，未注册目标同样计数），动态码与 Recovery Code 由 MFA 服务计在 Identity 上。原密码正确不清零计数：该计数也承载
 * 动态码失败，不能借改密码重置。改密码另外检查账号自身各登录标识的锁：账号被猜密码锁定时，持有 Access Token 也不能借改密码继续尝试；忘记密码不查，
 * 它凭的是另一个标识上的验证码，与用邮箱验证码登录同理。
 */
@Service
public class PasswordService {
    /** 自助改密码。 */
    public static final String CHANGE = "password.change";
    /** 忘记密码重设。 */
    public static final String RESET = "password.reset";

    // 验证码通过后完成重设（含 MFA）的时限，与待 MFA 状态同量级。
    private static final Duration RESET_TTL = Duration.ofMinutes(10);
    private static final String RESET_PREFIX = "iam:global:password-reset:";

    private final IdentityService identities;
    private final OperatorService operators;
    private final OperatorStateService operatorStates;
    private final MembershipStateService membershipStates;
    private final IdentityMfaService mfa;
    private final VerificationCodeService verifications;
    private final RedisAuthorizationService authorizations;
    private final LoginSessionRegistry loginSessions;
    private final PasswordPolicy policy;
    private final PasswordEncoder passwords;
    private final StringRedisTemplate redis;
    private final AuditRecorder audits;
    private final LoginAudit loginAudits;
    private final LoginLockoutService lockout;
    private final SecureRandom random = new SecureRandom();

    PasswordService(
            IdentityService identities,
            OperatorService operators,
            OperatorStateService operatorStates,
            MembershipStateService membershipStates,
            IdentityMfaService mfa,
            VerificationCodeService verifications,
            RedisAuthorizationService authorizations,
            LoginSessionRegistry loginSessions,
            PasswordPolicy policy,
            PasswordEncoder passwords,
            StringRedisTemplate redis,
            AuditRecorder audits,
            LoginAudit loginAudits,
            LoginLockoutService lockout) {
        this.identities = identities;
        this.operators = operators;
        this.operatorStates = operatorStates;
        this.membershipStates = membershipStates;
        this.mfa = mfa;
        this.verifications = verifications;
        this.authorizations = authorizations;
        this.loginSessions = loginSessions;
        this.policy = policy;
        this.passwords = passwords;
        this.redis = redis;
        this.audits = audits;
        this.loginAudits = loginAudits;
        this.lockout = lockout;
    }

    /**
     * Identity 凭原密码改密码。
     *
     * @param accessToken 发起请求的 Access Token，其所属授权在改密码后保留
     * @throws ForgeException 400 {@code password.currentInvalid} 或 {@code password.*}
     */
    public void changeIdentityPassword(
            UUID identityId, String currentPassword, String newPassword, String accessToken) {
        IdentityPassword identity = identities.findActivePassword(identityId).orElseThrow(() -> currentInvalid());
        LockoutTarget target = LockoutTarget.identity(identityId);
        lockout.check(target);
        checkIdentifiers(AuditRealm.TENANT, identity.account(), identity.email(), identity.phone());
        if (!passwords.matches(currentPassword, identity.passwordHash())) {
            lockout.failed(target);
            throw currentInvalid();
        }
        policy.checkIdentity(newPassword, identity.account(), identity.email(), identity.phone());
        if (!identities.changePassword(identityId, passwords.encode(newPassword), identityId)) throw currentInvalid();
        revokeIdentity(identityId, authorizationOf(accessToken));
        audits.record(identityAudit(CHANGE, identityId));
    }

    /**
     * Operator 凭原密码改密码，与管理员重置执行同一 Password Policy。
     *
     * @param accessToken 发起请求的 Access Token，其所属授权在改密码后保留
     * @throws ForgeException 400 {@code password.currentInvalid} 或 {@code password.*}
     */
    public void changeOperatorPassword(
            UUID operatorId, String currentPassword, String newPassword, String accessToken) {
        OperatorPassword operator = operators.findActivePassword(operatorId);
        if (operator == null) throw currentInvalid();
        LockoutTarget target = LockoutTarget.operator(operatorId);
        lockout.check(target);
        checkIdentifiers(AuditRealm.PLATFORM, operator.account());
        if (!passwords.matches(currentPassword, operator.passwordHash())) {
            lockout.failed(target);
            throw currentInvalid();
        }
        policy.checkOperator(newPassword, operator.account());
        operators.changePassword(operatorId, newPassword, operatorId);
        // 与管理员重置密码相同：递增版本使已签发的 Access Token 失效。
        operatorStates.incrementVersion(operatorId);
        authorizations.removeByPrincipal(operatorId.toString(), authorizationOf(accessToken));
        loginSessions.invalidateAll(operatorId.toString());
        audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.LOGIN, CHANGE)
                .actor(operatorId)
                .resource("operator", operatorId)
                .build());
    }

    /** 向邮箱或手机号发送重设验证码；未注册的目标同样生成验证码、计入发送间隔，只是不发送，响应一致。 */
    public CodeDelivery sendResetCode(String target) {
        boolean registered = identities.findActiveIdByContact(target).isPresent();
        return verifications.issue(VerificationPurpose.PASSWORD_RESET, target, registered);
    }

    /**
     * 校验重设验证码，通过后签发一次性重设凭证，并告知是否还须完成 MFA。
     *
     * @throws ForgeException 400 {@code verification.*}
     */
    public ResetTicket verifyResetCode(String target, String code) {
        LockoutTarget lockoutTarget = LockoutTarget.identifier(AuditRealm.TENANT, target);
        lockout.check(lockoutTarget);
        UUID identityId;
        try {
            verifications.verify(VerificationPurpose.PASSWORD_RESET, target, code);
            // 未注册的目标从未收到验证码，能走到这里只可能是猜中；与验证码错误同样处理，不暴露目标是否注册。
            identityId = identities
                    .findActiveIdByContact(target)
                    .orElseThrow(() -> ForgeException.withErrorMessage(
                            VerificationErrorCode.CODE_INVALID, "Verification code rejected"));
        } catch (ForgeException error) {
            lockout.failed(lockoutTarget);
            throw error;
        }
        lockout.succeeded(lockoutTarget);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(resetKey(token), identityId.toString(), RESET_TTL);
        return new ResetTicket(token, mfa.isBound(identityId));
    }

    /**
     * 以重设凭证设置新密码；已绑定 Authenticator 时须同时提供动态码或 Recovery Code。新密码或 MFA 不通过时凭证保留，可在有效期内重试。
     *
     * @throws ForgeException 400 {@code password.resetExpired}、{@code password.mfaRequired}、{@code password.*} 或
     *     {@code mfa.*}
     */
    public void reset(String resetToken, String newPassword, String code, String recoveryCode) {
        String key = resetKey(resetToken);
        String stored = redis.opsForValue().get(key);
        if (stored == null) throw resetExpired();
        UUID identityId = UUID.fromString(stored);
        IdentityPassword identity =
                identities.findActivePassword(identityId).orElseThrow(PasswordService::resetExpired);
        lockout.check(LockoutTarget.identity(identityId));
        policy.checkIdentity(newPassword, identity.account(), identity.email(), identity.phone());
        if (mfa.isBound(identityId)) {
            if (code == null && recoveryCode == null) {
                throw ForgeException.withErrorMessage(
                        PasswordErrorCode.MFA_REQUIRED, "MFA is required to reset the password");
            }
            verifyResetMfa(identityId, code, recoveryCode);
        }
        // 凭证在最后一刻才作废：并发提交同一凭证时只有一个能设置密码。
        if (redis.opsForValue().getAndDelete(key) == null) throw resetExpired();
        // 忘记密码没有认证上下文，标准列操作人为 null。
        if (!identities.changePassword(identityId, passwords.encode(newPassword), null)) throw resetExpired();
        revokeIdentity(identityId, null);
        audits.record(identityAudit(RESET, identityId));
    }

    // 动态码或 Recovery Code 错误由 MFA 服务计入该 Identity 的 Login Lockout。
    private void verifyResetMfa(UUID identityId, String code, String recoveryCode) {
        String method = code != null ? LoginAudit.MFA_TOTP : LoginAudit.MFA_RECOVERY_CODE;
        try {
            if (code != null) {
                mfa.verifyLoginCode(identityId, code);
            } else {
                mfa.useRecoveryCode(identityId, recoveryCode);
            }
        } catch (ForgeException error) {
            loginAudits.mfa(
                    AuditRealm.TENANT, identityId, method, error.errorCode().code());
            throw error;
        }
        loginAudits.mfa(AuditRealm.TENANT, identityId, method, null);
    }

    // 账号没有登记的标识（如未填手机号）为 null。
    private void checkIdentifiers(AuditRealm realm, String... identifiers) {
        for (String identifier : identifiers) {
            if (identifier != null) lockout.check(LockoutTarget.identifier(realm, identifier));
        }
    }

    // 已被吊销或过期的 Access Token 找不到授权，此时全部吊销。
    private String authorizationOf(String accessToken) {
        OAuth2Authorization authorization = authorizations.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN);
        return authorization == null ? null : authorization.getId();
    }

    private void revokeIdentity(UUID identityId, String keepAuthorizationId) {
        // Identity 没有自己的版本：递增其全部有效 Membership 的版本，网关据此拒绝已签发的 Access Token。
        for (ActiveMembership membership : identities.findActiveMemberships(identityId)) {
            membershipStates.incrementVersion(membership.membershipId());
        }
        authorizations.removeByPrincipal(identityId.toString(), keepAuthorizationId);
        loginSessions.invalidateAll(identityId.toString());
    }

    // 凭证变更与 MFA 绑定一样是全局事件，不属于任何租户。
    private static AuditEvent identityAudit(String action, UUID identityId) {
        return AuditEvent.builder(AuditRealm.TENANT, AuditCategory.LOGIN, action)
                .actor(identityId)
                .resource("identity", identityId)
                .build();
    }

    private static ForgeException currentInvalid() {
        return ForgeException.withErrorMessage(PasswordErrorCode.CURRENT_INVALID, "Current password rejected");
    }

    private static ForgeException resetExpired() {
        return ForgeException.withErrorMessage(PasswordErrorCode.RESET_EXPIRED, "Password reset ticket expired");
    }

    // 只存凭证的摘要，拿到 Redis 数据的人无法直接使用。
    private static String resetKey(String token) {
        try {
            return RESET_PREFIX
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
