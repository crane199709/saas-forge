package io.saas.forge.tenantaccess.config;

import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.brand.BrandProfileEventFactory;
import io.saas.forge.tenantaccess.application.brand.BrandProfileRepository;
import io.saas.forge.tenantaccess.application.brand.BrandProfileService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class BrandProfileConfiguration {
    @Bean
    BrandProfileEventFactory brandProfileEventFactory(ObjectMapper json, UuidV7Generator ids, Clock clock,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new BrandProfileEventFactory(json, ids, clock, topic);
    }

    @Bean
    BrandProfileService brandProfileService(PermissionAuthority authority, BrandProfileRepository profiles,
            OutboxEventRepository outbox, BrandProfileEventFactory events) {
        return new BrandProfileService(authority, profiles, outbox, events);
    }
}
