package io.saasforge.iam.mfa.service;

import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.mfa.mapper.IdentityAuthenticatorMapper;
import io.saasforge.iam.mfa.mapper.IdentityRecoveryCodeMapper;
import io.saasforge.iam.mfa.model.RecoveryCodeHash;
import io.saasforge.iam.mfa.model.StoredAuthenticator;
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
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Identity 自助管理 Authenticator 与 Recovery Code：两步绑定、重新生成 Recovery Code、解绑（账号安全规格 2.1–2.2）。
 *
 * <p>绑定分两步：开始时生成的密钥只加密暂存在 Redis，用户提交首个正确的动态码后才写库生效，扫码失败或放弃不会留下半成品。
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

    IdentityMfaService(
            IdentityAuthenticatorMapper authenticators,
            IdentityRecoveryCodeMapper recoveryCodes,
            IdentityService identities,
            SecretCipher cipher,
            StringRedisTemplate redis,
            AuditRecorder audits) {
        this.authenticators = authenticators;
        this.recoveryCodes = recoveryCodes;
        this.identities = identities;
        this.cipher = cipher;
        this.redis = redis;
        this.audits = audits;
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
        redis.opsForValue().set(PENDING_PREFIX + identityId, sealed, PENDING_TTL);
        return Totp.otpauthUri(ISSUER, name, secret);
    }

    /**
     * 以首个动态码确认绑定，Authenticator 随之生效；返回的 Recovery Code 只在此时给出明文，服务端只存哈希。
     *
     * <p>动态码错误时进行中的绑定保留，可以重试；并发确认时只有一个成功，其余视为已绑定。
     */
    @Transactional
    public List<String> confirmBinding(UUID identityId, String code) {
        requireUnbound(identityId);
        String key = PENDING_PREFIX + identityId;
        String sealed = redis.opsForValue().get(key);
        if (sealed == null) {
            throw ForgeException.withErrorMessage(MfaErrorCode.BINDING_EXPIRED, "No pending authenticator binding");
        }
        byte[] ciphertext = Base64.getDecoder().decode(sealed);
        long step = Totp.match(cipher.decrypt(ciphertext, owner(identityId)), code, Instant.now(), -1)
                .orElseThrow(IdentityMfaService::invalidCode);
        try {
            // 记下确认用的步长：首个动态码同样不能再用于随后的操作。
            authenticators.insert(UuidV7.generate(), identityId, ciphertext, step, identityId);
        } catch (DuplicateKeyException concurrent) {
            throw alreadyBound();
        }
        List<String> codes = issueRecoveryCodes(identityId);
        // 提交后才清除暂存：事务回滚时用户仍可用同一次扫码重试。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.delete(key);
            }
        });
        record(BIND, identityId);
        return codes;
    }

    /** 验证一次动态码后作废全部旧 Recovery Code，另发一组；明文只在本次返回。 */
    @Transactional
    public List<String> regenerateRecoveryCodes(UUID identityId, String code) {
        verifyCode(identityId, code);
        recoveryCodes.deleteByIdentity(identityId, identityId);
        List<String> codes = issueRecoveryCodes(identityId);
        record(REGENERATE, identityId);
        return codes;
    }

    /** 验证一次动态码后解绑，Recovery Code 一并作废。 */
    @Transactional
    public void unbind(UUID identityId, String code) {
        verifyCode(identityId, code);
        authenticators.deleteByIdentity(identityId, identityId);
        recoveryCodes.deleteByIdentity(identityId, identityId);
        record(UNBIND, identityId);
    }

    private void verifyCode(UUID identityId, String code) {
        StoredAuthenticator authenticator = authenticators.findByIdentity(identityId);
        if (authenticator == null) {
            throw ForgeException.withErrorMessage(MfaErrorCode.NOT_BOUND, "No authenticator bound");
        }
        byte[] secret = cipher.decrypt(authenticator.secretCiphertext(), owner(identityId));
        long step = Totp.match(secret, code, Instant.now(), authenticator.lastUsedStep())
                .orElseThrow(IdentityMfaService::invalidCode);
        // 条件更新保证并发提交同一动态码时只有一个通过。
        if (authenticators.advanceStep(authenticator.id(), step, identityId) == 0) throw invalidCode();
    }

    private void requireUnbound(UUID identityId) {
        if (authenticators.findByIdentity(identityId) != null) throw alreadyBound();
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
