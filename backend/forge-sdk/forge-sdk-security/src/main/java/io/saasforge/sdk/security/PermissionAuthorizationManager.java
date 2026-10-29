package io.saasforge.sdk.security;

import io.saasforge.sdk.core.MembershipGrants;
import io.saasforge.sdk.core.ResourceManifest;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.ClassUtils;

/**
 * 按 {@link RequiresPermission} 校验当前 Membership 的 Permission Code，明细取自 {@link MembershipGrantStore}（ADR-0005）。
 * Platform Realm 服务（{@code forge.security.realm=platform}）改为校验当前 Operator 的平台角色明细，两者共用同一套判断。
 *
 * <p>拒绝时由方法安全抛出 {@code AccessDeniedException}，经 {@link EnvelopeAccessDeniedHandler} 输出 403 信封。
 */
public final class PermissionAuthorizationManager implements AuthorizationManager<MethodInvocation> {
    private final ObjectProvider<MembershipGrantStore> grants;
    private final ObjectProvider<ForgeSecurityProperties> properties;

    /**
     * 创建校验器；依赖在首次校验时才取得。
     *
     * @param grants Membership 明细读取器；服务未配置 Redis 时不存在
     * @param properties 用于区分服务所属 Realm
     */
    public PermissionAuthorizationManager(
            ObjectProvider<MembershipGrantStore> grants, ObjectProvider<ForgeSecurityProperties> properties) {
        this.grants = grants;
        this.properties = properties;
    }

    /**
     * 当前 Membership（Platform Realm 服务中为当前 Operator）拥有方法（或其所在类）声明的 Permission Code 时放行。
     *
     * @throws IllegalStateException 服务未配置 Redis，无法读取权限明细
     */
    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, MethodInvocation invocation) {
        if (!(authentication.get() instanceof JwtAuthenticationToken)) return new AuthorizationDecision(false);
        MembershipGrantStore store = grants.getIfAvailable();
        // 缺少明细来源是配置错误，以 500 暴露，而不是让所有受保护接口看似正常地返回 403。
        if (store == null) throw new IllegalStateException("@RequiresPermission requires Redis for grants");
        String code = requiredPermission(invocation);
        // 解码器只接受本服务 Realm 的 Token，Token 的 claim 与这里选择的明细来源一致。
        MembershipGrants current =
                ResourceManifest.PLATFORM_REALM.equals(properties.getObject().realm())
                        ? store.grants(OperatorContext.current())
                        : store.current();
        return new AuthorizationDecision(current.permissions().contains(code));
    }

    private static String requiredPermission(MethodInvocation invocation) {
        Class<?> targetClass = invocation.getThis() == null
                ? invocation.getMethod().getDeclaringClass()
                : ClassUtils.getUserClass(invocation.getThis());
        Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
        RequiresPermission annotation = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPermission.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequiresPermission.class);
        }
        if (annotation == null) throw new IllegalStateException("No @RequiresPermission on " + method);
        return annotation.value();
    }
}
