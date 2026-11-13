package io.saasforge.iam.mfa.service;

import io.saasforge.iam.lockout.model.LockoutTarget;
import io.saasforge.iam.lockout.service.LoginLockoutService;
import io.saasforge.iam.mfa.mapper.OperatorAuthenticatorMapper;
import io.saasforge.iam.mfa.mapper.OperatorRecoveryCodeMapper;
import io.saasforge.iam.mfa.model.RecoveryCodeHash;
import io.saasforge.iam.mfa.model.StoredAuthenticator;
import io.saasforge.iam.operator.model.ActiveOperator;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.security.config.IamProperties;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator 的 Authenticator 与 Recovery Code（账号安全规格 2.1–2.3），TOTP、密钥加密与 Recovery Code 与 Identity 相同，分表存储。
 *
 * <p>与 Identity 的差别：{@code forge.iam.platform.mfa-required=true} 时不能解绑，只能更换——先验证旧动态码开始绑定新的，确认后新
 * Authenticator 取代旧的，Recovery Code 一并换新。
 *
 * <p>动态码校验都在事务之外：校验失败要计入 Login Lockout 并可能写 {@code auth.locked} 审计，放在事务内会随抛出的异常回滚而丢失审计。
 */
@Service
public class OperatorMfaService {
    private static final String BIND = "mfa.bind";
    private static final String UNBIND = "mfa.unbind";
    private static final String REGENERATE = "mfa.recoveryCodesRegenerate";

    // Operator 不属于任何租户，与 Identity 的进行中绑定一样使用 iam:global: 前缀。
    private static final String PENDING_PREFIX = "iam:global:mfa:binding:operator:";
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);
    // 进行中绑定的 Redis 值为「被取代的 Authenticator 标识|密钥密文」，新绑定时标识为空。
    private static final String SEPARATOR = "|";

    private final OperatorAuthenticatorMapper authenticators;
    private final OperatorRecoveryCodeMapper recoveryCodes;
    private final OperatorService operators;
    private final SecretCipher cipher;
    private final StringRedisTemplate redis;
    private final AuditRecorder audits;
    private final boolean required;
    private final LoginLockoutService lockout;
    private final TransactionTemplate transactions;

    OperatorMfaService(
            OperatorAuthenticatorMapper authenticators,
            OperatorRecoveryCodeMapper recoveryCodes,
            OperatorService operators,
            SecretCipher cipher,
            StringRedisTemplate redis,
            AuditRecorder audits,
            IamProperties properties,
            LoginLockoutService lockout,
            TransactionTemplate transactions) {
        this.authenticators = authenticators;
        this.recoveryCodes = recoveryCodes;
        this.operators = operators;
        this.cipher = cipher;
        this.redis = redis;
        this.audits = audits;
        this.required = properties.platform().mfaRequired();
        this.lockout = lockout;
        this.transactions = transactions;
    }

    // 同一键也由 MfaResetService 作废：重置前开始的绑定不能在重置后被确认。
    static String pendingKey(UUID operatorId) {
        return PENDING_PREFIX + operatorId;
    }

    /** 是否要求所有 Operator 使用 MFA（{@code forge.iam.platform.mfa-required}）。 */
    public boolean required() {
        return required;
    }

    /** 是否已绑定 Authenticator；已绑定的 Operator 每次登录都须在密码之后完成 MFA。 */
    public boolean isBound(UUID operatorId) {
        return authenticators.findByOperator(operatorId) != null;
    }

    /** 开始绑定：生成新密钥并暂存，覆盖未完成的上一次绑定；返回供验证器 App 扫码的 {@code otpauth://} URI。 */
    public String startBinding(UUID operatorId) {
        if (isBound(operatorId)) {
            throw alreadyBound();
        }
        return stage(operatorId, null);
    }

    /** 开始更换：先以旧 Authenticator 的动态码确认持有者，再按绑定流程暂存新密钥；确认后新的取代旧的。 */
    public String startReplacement(UUID operatorId, String currentCode) {
        StoredAuthenticator current = verifyCode(operatorId, currentCode, operatorId);
        return stage(operatorId, current.id());
    }

    /**
     * 以首个动态码确认绑定或更换，返回只在此时给出明文的 Recovery Code；更换时旧 Authenticator 与旧 Recovery Code 全部作废。
     *
     * <p>动态码错误时进行中的绑定保留，可以重试。暂存时记下了要取代的 Authenticator，确认时现状与之不符（期间被别处绑定、更换或重置）
     * 即拒绝，不能借一次旧码验证取代另一个 Authenticator。
     */
    public List<String> confirmBinding(UUID operatorId, String code) {
        return confirmBinding(operatorId, code, operatorId);
    }

    /** 登录中当场绑定：与 {@link #confirmBinding(UUID, String)} 相同，只是登录流程尚无认证上下文，标准列操作人为 null。 */
    public List<String> confirmLoginBinding(UUID operatorId, String code) {
        return confirmBinding(operatorId, code, null);
    }

    private List<String> confirmBinding(UUID operatorId, String code, UUID actor) {
        String key = pendingKey(operatorId);
        String pending = redis.opsForValue().get(key);
        if (pending == null) {
            throw ForgeException.withErrorMessage(MfaErrorCode.BINDING_EXPIRED, "No pending authenticator binding");
        }
        int split = pending.indexOf(SEPARATOR);
        String replaces = pending.substring(0, split);
        byte[] ciphertext = Base64.getDecoder().decode(pending.substring(split + 1));
        StoredAuthenticator current = authenticators.findByOperator(operatorId);
        String currentId = current == null ? "" : current.id().toString();
        if (!currentId.equals(replaces)) {
            if (replaces.isEmpty()) {
                throw alreadyBound();
            }
            throw ForgeException.withErrorMessage(MfaErrorCode.BINDING_EXPIRED, "Replaced authenticator changed");
        }
        LockoutTarget target = LockoutTarget.operator(operatorId);
        lockout.check(target);
        OptionalLong matched = Totp.match(cipher.decrypt(ciphertext, owner(operatorId)), code, Instant.now(), -1);
        if (matched.isEmpty()) {
            lockout.failed(target);
            throw invalidCode();
        }
        long step = matched.getAsLong();
        List<String> codes = transactions.execute(status -> {
            if (current != null) {
                authenticators.deleteByOperator(operatorId, actor);
                recoveryCodes.deleteByOperator(operatorId, actor);
            }
            try {
                authenticators.insert(UuidV7.generate(), operatorId, ciphertext, step, actor);
            } catch (DuplicateKeyException concurrent) {
                throw alreadyBound();
            }
            return issueRecoveryCodes(operatorId, actor);
        });
        redis.delete(key);
        var event = event(BIND, operatorId);
        if (current != null) {
            event.detail("replaced", true);
        }
        audits.record(event.build());
        return codes;
    }

    /** 登录第二步校验动态码，通过即用掉该码。登录流程尚无认证上下文，标准列操作人为 null。 */
    public void verifyLoginCode(UUID operatorId, String code) {
        verifyCode(operatorId, code, null);
    }

    /** 登录第二步以 Recovery Code 代替动态码，通过即作废该码；码错误或已用过时拒绝。 */
    public void useRecoveryCode(UUID operatorId, String recoveryCode) {
        if (!isBound(operatorId)) {
            throw ForgeException.withErrorMessage(MfaErrorCode.NOT_BOUND, "No authenticator bound");
        }
        LockoutTarget target = LockoutTarget.operator(operatorId);
        lockout.check(target);
        if (recoveryCodes.consume(operatorId, RecoveryCodes.hash(recoveryCode), null) == 0) {
            lockout.failed(target);
            throw ForgeException.withErrorMessage(MfaErrorCode.RECOVERY_CODE_INVALID, "Recovery code rejected");
        }
        lockout.succeeded(target);
    }

    /** 验证一次动态码后作废全部旧 Recovery Code，另发一组；明文只在本次返回。 */
    public List<String> regenerateRecoveryCodes(UUID operatorId, String code) {
        verifyCode(operatorId, code, operatorId);
        List<String> codes = transactions.execute(status -> {
            recoveryCodes.deleteByOperator(operatorId, operatorId);
            return issueRecoveryCodes(operatorId, operatorId);
        });
        audits.record(event(REGENERATE, operatorId).build());
        return codes;
    }

    /**
     * 验证一次动态码后解绑，Recovery Code 一并作废。
     *
     * @throws ForgeException 403 {@code mfa.unbindForbidden}：要求 Operator 使用 MFA 时只能更换；此时不校验也不消耗动态码
     */
    public void unbind(UUID operatorId, String code) {
        if (required) {
            throw ForgeException.withErrorMessage(MfaErrorCode.UNBIND_FORBIDDEN, "Operator MFA is required");
        }
        verifyCode(operatorId, code, operatorId);
        transactions.executeWithoutResult(status -> {
            authenticators.deleteByOperator(operatorId, operatorId);
            recoveryCodes.deleteByOperator(operatorId, operatorId);
        });
        audits.record(event(UNBIND, operatorId).build());
    }

    private String stage(UUID operatorId, UUID replaces) {
        ActiveOperator operator = operators.findActive(operatorId);
        if (operator == null) {
            throw ForgeException.withErrorMessage(FrameworkErrorCode.AUTH_UNAUTHENTICATED, "Operator unavailable");
        }
        byte[] secret = Totp.newSecret();
        String sealed = Base64.getEncoder().encodeToString(cipher.encrypt(secret, owner(operatorId)));
        String value = (replaces == null ? "" : replaces.toString()) + SEPARATOR + sealed;
        redis.opsForValue().set(pendingKey(operatorId), value, PENDING_TTL);
        return Totp.otpauthUri(IdentityMfaService.ISSUER, operator.account(), secret);
    }

    // 登录第二步、更换、重新生成与解绑都经过这里，动态码错误一并计入该 Operator 的 Login Lockout。
    private StoredAuthenticator verifyCode(UUID operatorId, String code, UUID actor) {
        StoredAuthenticator authenticator = authenticators.findByOperator(operatorId);
        if (authenticator == null) {
            throw ForgeException.withErrorMessage(MfaErrorCode.NOT_BOUND, "No authenticator bound");
        }
        LockoutTarget target = LockoutTarget.operator(operatorId);
        lockout.check(target);
        byte[] secret = cipher.decrypt(authenticator.secretCiphertext(), owner(operatorId));
        OptionalLong step = Totp.match(secret, code, Instant.now(), authenticator.lastUsedStep());
        if (step.isEmpty() || authenticators.advanceStep(authenticator.id(), step.getAsLong(), actor) == 0) {
            lockout.failed(target);
            throw invalidCode();
        }
        lockout.succeeded(target);
        return authenticator;
    }

    private List<String> issueRecoveryCodes(UUID operatorId, UUID actor) {
        List<String> codes = RecoveryCodes.generate();
        recoveryCodes.insertAll(
                operatorId,
                codes.stream()
                        .map(code -> new RecoveryCodeHash(UuidV7.generate(), RecoveryCodes.hash(code)))
                        .toList(),
                actor);
        return codes;
    }

    private static AuditEvent.Builder event(String action, UUID operatorId) {
        return AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.LOGIN, action)
                .actor(operatorId)
                .resource("operator", operatorId);
    }

    private static String owner(UUID operatorId) {
        return "operator:" + operatorId;
    }

    private static ForgeException alreadyBound() {
        return ForgeException.withErrorMessage(MfaErrorCode.ALREADY_BOUND, "Authenticator already bound");
    }

    private static ForgeException invalidCode() {
        return ForgeException.withErrorMessage(MfaErrorCode.CODE_INVALID, "Authenticator code rejected");
    }
}
