package io.saasforge.iam.lockout.model;

import io.saasforge.sdk.audit.AuditRealm;
import java.util.Locale;
import java.util.UUID;

/**
 * Login Lockout 的计数对象：First Factor 按规范化后的登录标识计数（不论标识是否存在），动态码、Recovery Code 与原密码按
 * Identity 或 Operator 计数（账号安全规格第 3 节）。
 *
 * @param realm 所属 Realm，决定适用的阈值
 * @param key 计数键中的对象部分
 * @param actor 能确定的 Identity 或 Operator，按登录标识计数时为 null
 * @param account 按登录标识计数时的规范化标识，否则为 null
 */
public record LockoutTarget(AuditRealm realm, String key, UUID actor, String account) {

    /** 按登录标识（账号、邮箱或手机号）计数：去掉首尾空白并忽略大小写，大小写变体不能绕过计数。 */
    public static LockoutTarget identifier(AuditRealm realm, String identifier) {
        String normalized = identifier.strip().toLowerCase(Locale.ROOT);
        return new LockoutTarget(realm, "identifier:" + normalized, null, normalized);
    }

    /** 按 Identity 计数。 */
    public static LockoutTarget identity(UUID identityId) {
        return new LockoutTarget(AuditRealm.TENANT, "identity:" + identityId, identityId, null);
    }

    /** 按 Operator 计数。 */
    public static LockoutTarget operator(UUID operatorId) {
        return new LockoutTarget(AuditRealm.PLATFORM, "operator:" + operatorId, operatorId, null);
    }
}
