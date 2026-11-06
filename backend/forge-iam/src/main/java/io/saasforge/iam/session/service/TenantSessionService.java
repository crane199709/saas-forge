package io.saasforge.iam.session.service;

import io.saasforge.iam.identity.model.ActiveMembership;
import io.saasforge.iam.identity.model.LoginCredential;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.lockout.model.LockoutTarget;
import io.saasforge.iam.lockout.service.LoginLockoutService;
import io.saasforge.iam.mfa.service.IdentityMfaService;
import io.saasforge.iam.security.service.MfaAuthority;
import io.saasforge.iam.session.model.PendingMfa;
import io.saasforge.iam.verification.model.CodeDelivery;
import io.saasforge.iam.verification.service.VerificationCodeService;
import io.saasforge.iam.verification.service.VerificationPurpose;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.core.api.ForgeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class TenantSessionService {
    // 与验证码有效期同量级：足够打开验证器 App 输入动态码，过期后须重新提交 First Factor（账号安全规格 2.3）。
    static final Duration PENDING_MFA_TTL = Duration.ofMinutes(5);

    private final IdentityService identities;
    private final IdentityMfaService mfa;
    private final VerificationCodeService verifications;
    private final PasswordEncoder passwords;
    private final LoginAudit audits;
    private final LoginLockoutService lockout;
    private final String dummyHash;

    TenantSessionService(
            IdentityService identities,
            IdentityMfaService mfa,
            VerificationCodeService verifications,
            PasswordEncoder passwords,
            LoginAudit audits,
            LoginLockoutService lockout) {
        this.identities = identities;
        this.mfa = mfa;
        this.verifications = verifications;
        this.passwords = passwords;
        this.audits = audits;
        this.lockout = lockout;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    // 只认证全局身份；没有或有多个有效 Membership 由调用方在密码校验通过后再处理。
    // 不存在的账号同样计数与锁定，锁定提示不泄露账号是否存在（账号安全规格第 3 节）。
    public Authentication authenticate(String account, String password) {
        LockoutTarget target = LockoutTarget.identifier(AuditRealm.TENANT, account);
        lockout.check(target);
        LoginCredential credential = identities.findCredential(account);
        String hash = credential == null ? dummyHash : credential.passwordHash();
        boolean valid = passwords.matches(password, hash);
        if (!valid || credential == null) {
            audits.failed(
                    AuditRealm.TENANT,
                    LoginAudit.LOGIN,
                    credential == null ? null : credential.identityId(),
                    account,
                    SessionErrorCode.AUTH_FAILED.code());
            lockout.failed(target);
            throw ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Bad credentials");
        }
        return firstFactorPassed(target, credential.identityId());
    }

    // 登录标识的计数清零；身份因动态码等失败被锁定时，换个标识通过 First Factor 同样被拒。
    private Authentication firstFactorPassed(LockoutTarget target, UUID identityId) {
        lockout.succeeded(target);
        lockout.check(LockoutTarget.identity(identityId));
        return authenticated(identityId, false);
    }

    // 未注册的目标同样生成验证码、计入发送间隔，只是不发送，响应与校验结果因此与已注册目标一致。
    public CodeDelivery sendLoginCode(String target) {
        boolean registered = identities.findActiveIdByContact(target).isPresent();
        return verifications.issue(VerificationPurpose.LOGIN, target, registered);
    }

    // 与密码登录建立同样的会话主体，之后的授权码与 Token 流程完全相同。
    public Authentication authenticateByCode(String target, String code) {
        LockoutTarget lockoutTarget = LockoutTarget.identifier(AuditRealm.TENANT, target);
        lockout.check(lockoutTarget);
        UUID identityId;
        try {
            verifications.verify(VerificationPurpose.LOGIN, target, code);
            // 发码后身份被停用或删除时才会找不到。
            identityId = identities
                    .findActiveIdByContact(target)
                    .orElseThrow(() ->
                            ForgeException.withErrorMessage(SessionErrorCode.AUTH_FAILED, "Identity unavailable"));
        } catch (ForgeException error) {
            audits.failed(
                    AuditRealm.TENANT,
                    LoginAudit.LOGIN,
                    null,
                    target,
                    error.errorCode().code());
            lockout.failed(lockoutTarget);
            throw error;
        }
        return firstFactorPassed(lockoutTarget, identityId);
    }

    /**
     * First Factor（密码或验证码，不论类型）通过后，已绑定 Authenticator 的身份进入待 MFA 状态；未绑定时为空，直接以
     * First Factor 的结果建立登录会话。
     */
    public Optional<PendingMfa> pendingMfa(Authentication firstFactor) {
        UUID identityId = UUID.fromString(firstFactor.getName());
        if (!mfa.isBound(identityId)) return Optional.empty();
        return Optional.of(new PendingMfa(identityId, Instant.now().plus(PENDING_MFA_TTL)));
    }

    /**
     * 以动态码或 Recovery Code（二者恰好提供一个）完成待 MFA 状态，返回带 MFA 标记的会话主体。
     *
     * <p>错误时待 MFA 状态保留，可在有效期内重试。
     *
     * @param pending 登录会话中的待 MFA 状态，没有时为 null
     */
    public Authentication completeMfa(PendingMfa pending, String code, String recoveryCode) {
        if (pending == null) {
            // 不知道是谁在提交，不记审计。
            throw ForgeException.withErrorMessage(SessionErrorCode.MFA_EXPIRED, "No pending MFA");
        }
        UUID identityId = pending.identityId();
        String method = code != null ? LoginAudit.MFA_TOTP : LoginAudit.MFA_RECOVERY_CODE;
        try {
            if (pending.expiredAt(Instant.now())) {
                throw ForgeException.withErrorMessage(SessionErrorCode.MFA_EXPIRED, "Pending MFA expired");
            }
            if (code != null) {
                mfa.verifyLoginCode(identityId, code);
            } else {
                mfa.useRecoveryCode(identityId, recoveryCode);
            }
        } catch (ForgeException error) {
            audits.mfa(AuditRealm.TENANT, identityId, method, error.errorCode().code());
            throw error;
        }
        audits.mfa(AuditRealm.TENANT, identityId, method, null);
        return authenticated(identityId, true);
    }

    private static Authentication authenticated(UUID identityId, boolean mfaCompleted) {
        // 会话只保留身份标识与是否完成 MFA，不序列化数据库投影或密码哈希；授权码流程把这个主体存入授权，供 refresh 与 switch 读取。
        var principal = User.withUsername(identityId.toString())
                .password("")
                .authorities(mfaCompleted ? new String[] {"TENANT", MfaAuthority.AUTHORITY} : new String[] {"TENANT"})
                .build();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    public List<ActiveMembership> memberships(UUID identityId) {
        return identities.findActiveMemberships(identityId);
    }
}
