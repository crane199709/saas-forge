package io.saasforge.sdk.security;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.ComposablePointcut;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.security.authorization.method.AuthorizationInterceptorsOrder;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;

/**
 * 让 {@link RequiresPermission} 生效，服务无需声明 {@code @EnableMethodSecurity}。
 *
 * <p>Spring Boot 默认注册的 AOP 自动代理只应用基础设施角色的 Advisor，因此拦截器以 {@code ROLE_INFRASTRUCTURE} 声明。
 */
@AutoConfiguration(after = {ForgeSecurityAutoConfiguration.class, MembershipGrantsAutoConfiguration.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PermissionAutoConfiguration {
    /**
     * 拦截方法或类上标注 {@link RequiresPermission} 的调用。
     *
     * <p>静态方法且延迟取得依赖：Advisor 在其他 Bean 之前创建，不能提前初始化 Redis 等组件。
     *
     * @param grants Membership 明细读取器
     * @param properties 用于区分服务所属 Realm
     * @return 方法安全拦截器
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor forgeRequiresPermissionAdvisor(
            ObjectProvider<MembershipGrantStore> grants, ObjectProvider<ForgeSecurityProperties> properties) {
        var pointcut = new ComposablePointcut(new AnnotationMatchingPointcut(RequiresPermission.class, true))
                .union(new AnnotationMatchingPointcut(null, RequiresPermission.class, true));
        var interceptor = new AuthorizationManagerBeforeMethodInterceptor(
                pointcut, new PermissionAuthorizationManager(grants, properties));
        interceptor.setOrder(AuthorizationInterceptorsOrder.PRE_AUTHORIZE.getOrder());
        return interceptor;
    }
}
