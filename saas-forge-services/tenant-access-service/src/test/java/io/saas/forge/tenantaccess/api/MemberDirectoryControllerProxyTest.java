package io.saas.forge.tenantaccess.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.saas.forge.sdk.tenant.TenantContextAccessor;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class MemberDirectoryControllerProxyTest {
    @Test
    void generatedMembersControllerStartsUnderWebMethodValidationClassProxy() {
        try (var context = new GenericWebApplicationContext(new MockServletContext())) {
            context.registerBean(TenantContextAccessor.class, () -> mock(TenantContextAccessor.class));
            context.registerBean(PermissionAuthority.class, () -> mock(PermissionAuthority.class));
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(MembershipDisableController.class, () -> mock(MembershipDisableController.class));
            context.registerBean(MembershipEnableController.class, () -> mock(MembershipEnableController.class));
            context.registerBean(MembershipOrganizationController.class, () -> mock(MembershipOrganizationController.class));
            context.registerBean(MembershipRoleController.class, () -> mock(MembershipRoleController.class));
            context.registerBean(LocalValidatorFactoryBean.class);
            context.registerBean(MethodValidationPostProcessor.class, () -> {
                var processor = new MethodValidationPostProcessor();
                processor.setProxyTargetClass(true);
                return processor;
            });
            context.registerBean(MemberDirectoryController.class);
            context.registerBean(RequestMappingHandlerMapping.class);

            context.refresh();

            assertThat(AopUtils.isCglibProxy(context.getBean(MemberDirectoryController.class))).isTrue();
            assertThat(context.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                    .anySatisfy(mapping -> assertThat(mapping.getPatternValues())
                            .contains("/api/v1/members/{membershipId}/disables"));
        }
    }
}
