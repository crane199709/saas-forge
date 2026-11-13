package io.saasforge.iam.session.service;

import io.saasforge.sdk.audit.AuditCategory;
import io.saasforge.sdk.audit.AuditEvent;
import io.saasforge.sdk.audit.AuditRealm;
import io.saasforge.sdk.audit.AuditRecorder;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * 两个 Realm 的登录类 Audit Event：登录成功、失败、MFA 步骤、退出与切换租户。
 *
 * <p>登录成功记在 Token 交换时：此时才确定进入哪个租户，Tenant Admin 据此看到本租户的登录。登录失败与退出发生在全局登录会话上，
 * 不属于任何租户，Tenant Realm 的这两类事件不带租户，Tenant Admin 查不到。
 */
@Service
public class LoginAudit {
    /** 登录（含验证码登录）。 */
    public static final String LOGIN = "auth.login";
    /** 登录第二步：提交动态码或 Recovery Code。 */
    public static final String MFA = "auth.mfa";
    /** {@link #MFA} 事件以动态码完成。 */
    public static final String MFA_TOTP = "totp";
    /** {@link #MFA} 事件以 Recovery Code 完成。 */
    public static final String MFA_RECOVERY_CODE = "recoveryCode";
    /** 退出登录会话。 */
    public static final String LOGOUT = "auth.logout";
    /** 切换租户。 */
    public static final String SWITCH_TENANT = "auth.switchTenant";

    private final AuditRecorder audits;

    LoginAudit(AuditRecorder audits) {
        this.audits = audits;
    }

    /** 以 Membership 进入租户：登录完成或切换租户成功。 */
    void entered(String action, UUID identityId, UUID tenantId, UUID membershipId) {
        audits.record(AuditEvent.builder(AuditRealm.TENANT, AuditCategory.LOGIN, action)
                .tenant(tenantId)
                .actor(identityId)
                .resource("membership", membershipId)
                .build());
    }

    /** Operator 登录完成。 */
    void operatorEntered(UUID operatorId) {
        audits.record(AuditEvent.builder(AuditRealm.PLATFORM, AuditCategory.LOGIN, LOGIN)
                .actor(operatorId)
                .resource("operator", operatorId)
                .build());
    }

    /**
     * 登录或切换租户失败。
     *
     * @param actor 能确定的身份，如密码错误时的账号所有者；账号不存在时为 null
     * @param account 尝试登录的账号、邮箱或手机号，便于发现撞库；切换租户时为 null
     */
    void failed(AuditRealm realm, String action, UUID actor, String account, String errorCode) {
        audits.record(AuditEvent.builder(realm, AuditCategory.LOGIN, action)
                .actor(actor)
                .detail("account", account)
                .failure(errorCode)
                .build());
    }

    /**
     * MFA 步骤成功或失败。与登录失败一样发生在全局登录会话上，不带租户。
     *
     * @param method {@link #MFA_TOTP} 或 {@link #MFA_RECOVERY_CODE}
     * @param errorCode 失败时的错误码，成功时为 null
     */
    public void mfa(AuditRealm realm, UUID actor, String method, String errorCode) {
        var event =
                AuditEvent.builder(realm, AuditCategory.LOGIN, MFA).actor(actor).detail("method", method);
        if (errorCode != null) {
            event.failure(errorCode);
        }
        audits.record(event.build());
    }

    /** 退出登录会话；未登录时的退出请求不记录。会话的 Realm 由登录时授予的权限区分。 */
    public void loggedOut(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }
        boolean operator = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("OPERATOR"::equals);
        audits.record(
                AuditEvent.builder(operator ? AuditRealm.PLATFORM : AuditRealm.TENANT, AuditCategory.LOGIN, LOGOUT)
                        .actor(UUID.fromString(authentication.getName()))
                        .build());
    }
}
