package io.saasforge.iam.mfa.service;

import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.lockout.model.LockoutTarget;
import io.saasforge.iam.lockout.service.LoginLockoutService;
import io.saasforge.iam.mfa.mapper.IdentityAuthenticatorMapper;
import io.saasforge.iam.mfa.mapper.IdentityRecoveryCodeMapper;
import io.saasforge.iam.mfa.model.RecoveryCodeHash;
import io.saasforge.iam.mfa.model.StoredAuthenticator;
import io.saasforge.iam.security.service.LoginSessionRegistry;
import io.saasforge.iam.security.service.MfaAuthority;
import io.saasforge.iam.security.service.MfaCompletionService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.core.api.FrameworkErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Identity 自助管理 Authenticator 与 Recovery Code：两步绑定、重新生成 Recovery Code、解绑（账号安全规格 2.1–2.2），
 * 以及登录第二步的动态码与 Recovery Code 校验（规格 2.3）。
 *
 * <p>绑定分两步：开始时生成的密钥只加密暂存在 Redis，用户提交首个正确的动态码后才写库生效，扫码失败或放弃不会留下半成品。
 *
 * <p>动态码校验都在事务之外：校验失败要计入 Login Lockout 并可能写 {@code auth.locked} 审计，放在事务内会随抛出的异常回滚而丢失审计。
 */
@Service
public class IdentityMfaService {
    /** 用户在验证器 App 中看到的服务名。 */
    static final String ISSUER = "SaaS Forge";

    private static final String BIND = "mfa.bind";
    private static final String UNBIND = "mfa.unbind";
    private static final String REGENERATE = "mfa.recoveryCodesRegenerate";

    // Authenticator 属于全局 Identity，与认证前的验证码一样使用 iam:global: 前缀。
    private static final String PENDING_PREFIX = "iam:global:mfa:binding:identity:";
    // 足够扫码并输入首个动态码，过期后重新开始。
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final IdentityAuthenticatorMapper authenticators;
    private final IdentityRecoveryCodeMapper recoveryCodes;
    private final IdentityService identities;
    private final SecretCipher cipher;
    private final StringRedisTemplate redis;
    private final AuditRecorder audits;
    private final MfaCompletionService completions;
    private final LoginLockoutService lockout;
    private final LoginSessionRegistry loginSessions;
    private final TransactionTemplate transactions;

    IdentityMfaService(
            IdentityAuthenticatorMapper authenticators,
            IdentityRecoveryCodeMapper recoveryCodes,
            IdentityService identities,
            SecretCipher cipher,
            StringRedisTemplate redis,
            AuditRecorder audits,
            MfaCompletionService completions,
            LoginLockoutService lockout,
            LoginSessionRegistry loginSessions,
            TransactionTemplate transactions) {
        this.authenticators = authenticators;
        this.recoveryCodes = recoveryCodes;
        this.identities = identities;
        this.cipher = cipher;
        this.redis = redis;
        this.audits = audits;
        this.completions = completions;
        this.lockout = lockout;
        this.loginSessions = loginSessions;
        this.transactions = transactions;
    }

    // 同一键也由 MfaResetService 作废：重置前开始的绑定不能在重置后被确认。
    static String pendingKey(UUID identityId) {
        return PENDING_PREFIX + identityId;
    }

    /** 开始绑定：生成新密钥并暂存，覆盖未完成的上一次绑定；返回供验证器 App 扫码的 {@code otpauth://} URI。 */
    public String startBinding(UUID identityId) {
        requireUnbound(identityId);
        String name = identities
                .findLoginName(identityId)
                .orElseThrow(() -> ForgeException.withErrorMessage(
                        FrameworkErrorCode.AUTH_UNAUTHENTICATED, "Identity unavailable"));
        byte[] secret = Totp.newSecret();
        String sealed = Base64.getEncoder().encodeToString(cipher.encrypt(secret, owner(identityId)));
        redis.opsForValue().set(pendingKey(identityId), sealed, PENDING_TTL);
        return Totp.otpauthUri(ISSUER, name, secret);
    }

    /**
     * 以首个动态码确认绑定，Authenticator 随之生效；返回的 Recovery Code 只在此时给出明文，服务端只存哈希。
     *
     * <p>动态码错误时进行中的绑定保留，可以重试；并发确认时只有一个成功，其余视为已绑定。
     */
    public List<String> confirmBinding(UUID identityId, String code) {
        requireUnbound(identityId);
        String key = pendingKey(identityId);
        String sealed = redis.opsForValue().get(key);
        if (sealed == null) {
            throw ForgeException.withErrorMessage(MfaErrorCode.BINDING_EXPIRED, "No pending authenticator binding");
        }
        byte[] ciphertext = Base64.getDecoder().decode(sealed);
        LockoutTarget target = LockoutTarget.identity(identityId);
        lockout.check(target);
        OptionalLong matched = Totp.match(cipher.decrypt(ciphertext, owner(identityId)), code, Instant.now(), -1);
        if (matched.isEmpty()) {
            lockout.failed(target);
            throw invalidCode();
        }
        long step = matched.getAsLong();
        List<String> codes = transactions.execute(status -> {
            try {
                // 记下确认用的步长：首个动态码同样不能再用于随后的操作。
                authenticators.insert(UuidV7.generate(), identityId, ciphertext, step, identityId);
            } catch (DuplicateKeyException concurrent) {
                throw alreadyBound();
            }
            return issueRecoveryCodes(identityId);
        });
        // 提交后才清除暂存：事务回滚时用户仍可用同一次扫码重试。
        redis.delete(key);
        record(BIND, identityId);
        return codes;
    }

    /**
     * 当场绑定后，凭 Access Token 绑定时把该 Token 所属授权记为已完成 MFA，被 MFA Requirement 拦下的 switch、refresh 可直接重试
     * （ADR-0019）。须在 {@link #confirmBinding} 提交之后调用。
     */
    public void completeAuthorization(UUID identityId, String accessToken) {
        completions.completeAuthorization(identityId, accessToken);
    }

    /** 当场绑定后，凭登录会话绑定时该会话视为已完成 MFA：返回补记了 MFA 的会话主体，由调用方存回会话。 */
    public Authentication completeSession(Authentication sessionPrincipal) {
        return MfaAuthority.grant(sessionPrincipal);
    }

    /** 登记换发后的会话标识：改密码按登记表终止会话，漏登记的会话会存活下来（账号安全规格第 5 节）。 */
    public void registerSession(UUID identityId, String sessionId) {
        loginSessions.register(identityId.toString(), sessionId);
    }

    /** 是否已绑定 Authenticator；已绑定的身份每次登录都须在 First Factor 之后完成 MFA（ADR-0019）。 */
    public boolean isBound(UUID identityId) {
        return authenticators.findByIdentity(identityId) != null;
    }

    /** 登录第二步校验动态码，通过即用掉该码。登录流程尚无认证上下文，标准列操作人为 null。 */
    public void verifyLoginCode(UUID identityId, String code) {
        verifyCode(identityId, code, null);
    }

    /** 登录第二步以 Recovery Code 代替动态码，通过即作废该码；码错误或已用过时拒绝。 */
    public void useRecoveryCode(UUID identityId, String recoveryCode) {
        if (!isBound(identityId)) {
            throw ForgeException.withErrorMessage(MfaErrorCode.NOT_BOUND, "No authenticator bound");
        }
        LockoutTarget target = LockoutTarget.identity(identityId);
        lockout.check(target);
        // 条件更新保证同一码并发提交时只有一个通过。
        if (recoveryCodes.consume(identityId, RecoveryCodes.hash(recoveryCode), null) == 0) {
            lockout.failed(target);
            throw ForgeException.withErrorMessage(MfaErrorCode.RECOVERY_CODE_INVALID, "Recovery code rejected");
        }
        lockout.succeeded(target);
    }

    /** 验证一次动态码后作废全部旧 Recovery Code，另发一组；明文只在本次返回。 */
    public List<String> regenerateRecoveryCodes(UUID identityId, String code) {
        verifyCode(identityId, code, identityId);
        List<String> codes = transactions.execute(status -> {
            recoveryCodes.deleteByIdentity(identityId, identityId);
            return issueRecoveryCodes(identityId);
        });
        record(REGENERATE, identityId);
        return codes;
    }

    /** 验证一次动态码后解绑，Recovery Code 一并作废，现存授权与登录会话不再视为已完成 MFA（账号安全规格 2.2）。 */
    public void unbind(UUID identityId, String code) {
        verifyCode(identityId, code, identityId);
        transactions.executeWithoutResult(status -> {
            authenticators.deleteByIdentity(identityId, identityId);
            recoveryCodes.deleteByIdentity(identityId, identityId);
        });
        completions.revokeCompletions(identityId);
        record(UNBIND, identityId);
    }

    // 登录第二步、重新生成、解绑与忘记密码重设都经过这里，动态码错误一并计入该 Identity 的 Login Lockout。
    private void verifyCode(UUID identityId, String code, UUID actor) {
        StoredAuthenticator authenticator = authenticators.findByIdentity(identityId);
        if (authenticator == null) {
            throw ForgeException.withErrorMessage(MfaErrorCode.NOT_BOUND, "No authenticator bound");
        }
        LockoutTarget target = LockoutTarget.identity(identityId);
        lockout.check(target);
        byte[] secret = cipher.decrypt(authenticator.secretCiphertext(), owner(identityId));
        OptionalLong step = Totp.match(secret, code, Instant.now(), authenticator.lastUsedStep());
        // 条件更新保证并发提交同一动态码时只有一个通过。
        if (step.isEmpty() || authenticators.advanceStep(authenticator.id(), step.getAsLong(), actor) == 0) {
            lockout.failed(target);
            throw invalidCode();
        }
        lockout.succeeded(target);
    }

    private void requireUnbound(UUID identityId) {
        if (authenticators.findByIdentity(identityId) != null) {
            throw alreadyBound();
        }
    }

    private List<String> issueRecoveryCodes(UUID identityId) {
        List<String> codes = RecoveryCodes.generate();
        recoveryCodes.insertAll(
                identityId,
                codes.stream()
                        .map(code -> new RecoveryCodeHash(UuidV7.generate(), RecoveryCodes.hash(code)))
                        .toList(),
                identityId);
        return codes;
    }

    // Authenticator 是全局凭证，不属于任何租户，事件不带租户（与登录会话上的事件一致）。
    private void record(String action, UUID identityId) {
        audits.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.LOGIN, action)
                .actor(identityId)
                .resource("identity", identityId)
                .build());
    }

    private static String owner(UUID identityId) {
        return "identity:" + identityId;
    }

    private static ForgeException alreadyBound() {
        return ForgeException.withErrorMessage(MfaErrorCode.ALREADY_BOUND, "Authenticator already bound");
    }

    private static ForgeException invalidCode() {
        return ForgeException.withErrorMessage(MfaErrorCode.CODE_INVALID, "Authenticator code rejected");
    }
}
