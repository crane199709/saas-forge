package io.saas.forge.tenantaccess.config;

import io.saas.forge.tenantaccess.application.tenant.TenantDetailsEventFactory;
import io.saas.forge.tenantaccess.application.tenant.TenantDetailsRepository;
import io.saas.forge.tenantaccess.application.tenant.TenantDetailsService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class TenantDetailsConfiguration {
    @Bean
    TenantDetailsEventFactory tenantDetailsEventFactory(ObjectMapper objectMapper, UuidV7Generator ids,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new TenantDetailsEventFactory(objectMapper, ids, topic);
    }

    @Bean
    TenantDetailsService tenantDetailsService(TenantDetailsRepository details,
            OutboxEventRepository outbox, TenantDetailsEventFactory events, Clock clock) {
        return new TenantDetailsService(details, outbox, events, clock);
    }
}
