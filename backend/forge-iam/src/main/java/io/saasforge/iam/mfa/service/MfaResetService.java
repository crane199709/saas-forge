package io.saasforge.iam.mfa.service;

import io.saasforge.iam.identity.model.IdentitySummary;
import io.saasforge.iam.identity.service.IdentityService;
import io.saasforge.iam.mfa.mapper.IdentityAuthenticatorMapper;
import io.saasforge.iam.mfa.mapper.IdentityRecoveryCodeMapper;
import io.saasforge.iam.mfa.mapper.OperatorAuthenticatorMapper;
import io.saasforge.iam.mfa.mapper.OperatorRecoveryCodeMapper;
import io.saasforge.iam.mfa.model.IdentityMfaStatus;
import io.saasforge.iam.operator.service.OperatorService;
import io.saasforge.iam.permission.service.PlatformPermissions;
import io.saasforge.iam.platformrole.service.PlatformRoleService;
import io.saasforge.iam.security.service.MfaCompletionService;
import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import io.saasforge.sdk.core.api.ForgeException;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator 执行的 MFA Reset（账号安全规格 2.5）：清除 Authenticator 与全部 Recovery Code，并撤销现存授权与登录会话上的 MFA 记录，被重置者下次登录不再要求 MFA，可重新绑定。
 * Identity 由持有 {@value PlatformPermissions#IDENTITY_MFA_RESET} 的 Operator 重置，Operator 只能由超级管理员重置；
 * 租户侧不提供重置他人 MFA 的入口，也不提供自助重置。
 *
 * <p>查找 Identity 只做精确匹配，只返回标识、展示名与是否已绑定，避免借此浏览身份或获知其 Membership。
 */
@Service
public class MfaResetService {
    private static final String RESET = "mfa.reset";

    private final IdentityAuthenticatorMapper identityAuthenticators;
    private final IdentityRecoveryCodeMapper identityRecoveryCodes;
    private final OperatorAuthenticatorMapper operatorAuthenticators;
    private final OperatorRecoveryCodeMapper operatorRecoveryCodes;
    private final IdentityService identities;
    private final OperatorService operators;
    private final PlatformRoleService roles;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final MfaCompletionService completions;
    private final AuditRecorder audits;

    MfaResetService(
            IdentityAuthenticatorMapper identityAuthenticators,
            IdentityRecoveryCodeMapper identityRecoveryCodes,
            OperatorAuthenticatorMapper operatorAuthenticators,
            OperatorRecoveryCodeMapper operatorRecoveryCodes,
            IdentityService identities,
            OperatorService operators,
            PlatformRoleService roles,
            TransactionTemplate transactions,
            StringRedisTemplate redis,
            MfaCompletionService completions,
            AuditRecorder audits) {
        this.identityAuthenticators = identityAuthenticators;
        this.identityRecoveryCodes = identityRecoveryCodes;
        this.operatorAuthenticators = operatorAuthenticators;
        this.operatorRecoveryCodes = operatorRecoveryCodes;
        this.identities = identities;
        this.operators = operators;
        this.roles = roles;
        this.transactions = transactions;
        this.redis = redis;
        this.completions = completions;
        this.audits = audits;
    }

    /**
     * 按账号、邮箱（不区分大小写）或手机号精确查找 Identity。
     *
     * @throws ForgeException 403 {@code auth.forbidden}；404 {@code mfa.identityNotFound}
     */
    public IdentityMfaStatus lookupIdentity(UUID actor, String identifier) {
        roles.requirePermission(actor, PlatformPermissions.IDENTITY_MFA_RESET);
        IdentitySummary identity = identities
                .findByLoginIdentifier(identifier.strip())
                .orElseThrow(() -> new ForgeException(MfaErrorCode.IDENTITY_NOT_FOUND));
        return new IdentityMfaStatus(
                identity.identityId(),
                identity.displayName(),
                identityAuthenticators.findByIdentity(identity.identityId()) != null);
    }

    /**
     * 重置 Identity 的 MFA。
     *
     * @throws ForgeException 403 {@code auth.forbidden}；404 {@code mfa.identityNotFound}；409 {@code mfa.notBound}
     */
    public void resetIdentity(UUID actor, UUID identityId) {
        roles.requirePermission(actor, PlatformPermissions.IDENTITY_MFA_RESET);
        if (identities.findLoginName(identityId).isEmpty()) {
            throw new ForgeException(MfaErrorCode.IDENTITY_NOT_FOUND);
        }
        transactions.executeWithoutResult(status -> {
            if (identityAuthenticators.deleteByIdentity(identityId, actor) == 0) throw notBound();
            identityRecoveryCodes.deleteByIdentity(identityId, actor);
        });
        // 进行中的绑定一并作废，免得重置前开始的绑定在重置后被确认。
        redis.delete(IdentityMfaService.pendingKey(identityId));
        // 重置常因设备丢失或泄露，已完成 MFA 的记录不能继续生效，否则被重置者的旧授权仍能进入有 MFA Requirement 的 Tenant。
        completions.revokeCompletions(identityId);
        audits.record(event(actor, "identity", identityId));
    }

    /**
     * 由超级管理员重置 Operator 的 MFA；{@code mfa-required=true} 时被重置者下次登录进入「必须绑定」。
     *
     * @throws ForgeException 403 {@code auth.forbidden}；404 {@code operator.notFound}；409 {@code mfa.notBound}
     */
    public void resetOperator(UUID actor, UUID operatorId) {
        roles.requireSuperAdmin(actor);
        operators.get(operatorId);
        transactions.executeWithoutResult(status -> {
            if (operatorAuthenticators.deleteByOperator(operatorId, actor) == 0) throw notBound();
            operatorRecoveryCodes.deleteByOperator(operatorId, actor);
        });
        redis.delete(OperatorMfaService.pendingKey(operatorId));
        completions.revokeCompletions(operatorId);
        audits.record(event(actor, "operator", operatorId));
    }

    private static AuditEvent event(UUID actor, String resourceType, UUID resourceId) {
        return AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.LOGIN, RESET)
                .actor(actor)
                .resource(resourceType, resourceId)
                .build();
    }

    private static ForgeException notBound() {
        return ForgeException.withErrorMessage(MfaErrorCode.NOT_BOUND, "No authenticator bound");
    }
}
