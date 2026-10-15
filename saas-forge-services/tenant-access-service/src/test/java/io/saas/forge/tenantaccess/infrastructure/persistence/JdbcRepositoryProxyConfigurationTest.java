package io.saas.forge.tenantaccess.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceRepository;
import io.saas.forge.tenantaccess.application.organization.MembershipOrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcRepositoryProxyConfigurationTest {

    @Test
    void repositoryExceptionTranslationCreatesClassProxiesForInvitationAndMembershipOrganization() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(
                    new DriverManagerDataSource("jdbc:postgresql://localhost:1/unused", "unused", "unused")));
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
                var processor = new PersistenceExceptionTranslationPostProcessor();
                processor.setProxyTargetClass(true);
                return processor;
            });
            context.register(JdbcInvitationAcceptanceRepository.class, JdbcMembershipOrganizationRepository.class);

            context.refresh();

            assertThat(AopUtils.isCglibProxy(context.getBean(InvitationAcceptanceRepository.class))).isTrue();
            assertThat(AopUtils.isCglibProxy(context.getBean(MembershipOrganizationRepository.class))).isTrue();
        }
    }
}
