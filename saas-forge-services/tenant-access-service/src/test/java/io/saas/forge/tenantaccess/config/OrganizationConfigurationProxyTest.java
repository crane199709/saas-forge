package io.saas.forge.tenantaccess.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.organization.MembershipOrganizationRepository;
import io.saas.forge.tenantaccess.application.organization.MembershipOrganizationService;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitRepository;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.ObjectMapper;

class OrganizationConfigurationProxyTest {
    @Test
    void productionOrganizationBeansKeepClassBasedTransactionAdvice() {
        var authority = mock(PermissionAuthority.class);
        var memberships = mock(MembershipOrganizationRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        var transaction = mock(TransactionStatus.class);
        var tenantId = UUID.fromString("019535d9-0001-7000-8000-000000000001");
        var membershipId = UUID.fromString("019535d9-0001-7000-8000-000000000002");
        var contextSnapshot = new TenantContextSnapshot(tenantId, membershipId,
                UUID.fromString("019535d9-0001-7000-8000-000000000003"));
        when(authority.allowed(contextSnapshot, "system:organization:manage")).thenReturn(true);
        when(memberships.list(tenantId, membershipId)).thenReturn(List.of());
        when(manager.getTransaction(any())).thenReturn(transaction);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "organization-test", Map.of("saas.forge.tenant-access.outbox-topic", "tenant-events")));
            context.registerBean(PermissionAuthority.class, () -> authority);
            context.registerBean(OrganizationUnitRepository.class, () -> mock(OrganizationUnitRepository.class));
            context.registerBean(MembershipOrganizationRepository.class, () -> memberships);
            context.registerBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(UuidV7Generator.class, () -> mock(UuidV7Generator.class));
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(PlatformTransactionManager.class, () -> manager);
            context.register(OrganizationConfiguration.class, ClassProxyTransactions.class);

            context.refresh();

            assertThat(AopUtils.isCglibProxy(context.getBean(OrganizationUnitService.class))).isTrue();
            var membershipService = context.getBean(MembershipOrganizationService.class);
            assertThat(AopUtils.isCglibProxy(membershipService)).isTrue();
            assertThat(membershipService.list(contextSnapshot, membershipId)).isEmpty();
            verify(manager).getTransaction(any());
            verify(manager).commit(transaction);
        }
    }

    @EnableTransactionManagement(proxyTargetClass = true)
    static class ClassProxyTransactions {}
}
