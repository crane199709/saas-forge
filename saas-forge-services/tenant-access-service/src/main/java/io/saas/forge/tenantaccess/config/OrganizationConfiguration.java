package io.saas.forge.tenantaccess.config;

import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitEventFactory;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitRepository;
import io.saas.forge.tenantaccess.application.organization.OrganizationUnitService;
import io.saas.forge.tenantaccess.application.organization.MembershipOrganizationEventFactory;
import io.saas.forge.tenantaccess.application.organization.MembershipOrganizationRepository;
import io.saas.forge.tenantaccess.application.organization.MembershipOrganizationService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class OrganizationConfiguration {
    @Bean
    OrganizationUnitEventFactory organizationUnitEventFactory(ObjectMapper json, UuidV7Generator ids,
            Clock clock, @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new OrganizationUnitEventFactory(json, ids, clock, topic);
    }

    @Bean
    OrganizationUnitService organizationUnitService(PermissionAuthority authority, OrganizationUnitRepository repository,
            OrganizationUnitEventFactory events, OutboxEventRepository outbox) {
        return new OrganizationUnitService(authority, repository, events, outbox);
    }

    @Bean
    MembershipOrganizationEventFactory membershipOrganizationEventFactory(ObjectMapper json, UuidV7Generator ids,
            Clock clock, @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new MembershipOrganizationEventFactory(json, ids, clock, topic);
    }

    @Bean
    MembershipOrganizationService membershipOrganizationService(PermissionAuthority authority,
            MembershipOrganizationRepository repository, MembershipOrganizationEventFactory events,
            OutboxEventRepository outbox) {
        return new MembershipOrganizationService(authority, repository, events, outbox);
    }
}
