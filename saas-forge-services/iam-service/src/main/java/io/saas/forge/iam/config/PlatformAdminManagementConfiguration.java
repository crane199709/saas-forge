package io.saas.forge.iam.config;

import io.saas.forge.iam.application.authentication.PasswordSetupDeliveryService;
import io.saas.forge.iam.application.authentication.UuidV7Generator;
import io.saas.forge.iam.application.authorization.PlatformAdminGrantService;
import io.saas.forge.iam.application.authorization.PlatformAdminDeliveryWorker;
import io.saas.forge.iam.application.authorization.PlatformAdminGrantTransaction;
import io.saas.forge.iam.application.authorization.PlatformAdminGrantedEventFactory;
import io.saas.forge.iam.application.authorization.PlatformAdminQueries;
import io.saas.forge.iam.application.authorization.PlatformAdminRevokeService;
import io.saas.forge.iam.application.authorization.PlatformAdminRevokeTransaction;
import io.saas.forge.iam.application.authorization.PlatformAdminRevokeWorker;
import io.saas.forge.iam.application.authorization.PlatformAdminRevokedEventFactory;
import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.application.authorization.PlatformAdminSetupCaller;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.iam.domain.identity.IdentityRepository;
import io.saas.forge.iam.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class PlatformAdminManagementConfiguration {
    @Bean
    PlatformAdminRevokedEventFactory platformAdminRevokedEventFactory(ObjectMapper json,
            UuidV7Generator ids, @Value("${saas.forge.environment:dev}") String environment) {
        return new PlatformAdminRevokedEventFactory(json, ids, environment);
    }

    @Bean
    PlatformAdminRevokeTransaction platformAdminRevokeTransaction(JdbcTemplate jdbc,
            OutboxEventRepository outbox, PlatformAdminRevokedEventFactory events, Clock clock) {
        return new PlatformAdminRevokeTransaction(jdbc, outbox, events, clock);
    }

    @Bean
    PlatformAdminRevokeService platformAdminRevokeService(PlatformAdminRevokeTransaction transaction,
            RevocationIndex index, Clock clock) {
        return new PlatformAdminRevokeService(transaction, index, clock);
    }

    @Bean
    PlatformAdminRevokeWorker platformAdminRevokeWorker(PlatformAdminRevokeService service) {
        return new PlatformAdminRevokeWorker(service);
    }
    @Bean
    PlatformAdminGrantedEventFactory platformAdminGrantedEventFactory(ObjectMapper json,
            UuidV7Generator ids, @Value("${saas.forge.environment:dev}") String environment) {
        return new PlatformAdminGrantedEventFactory(json, ids, environment);
    }

    @Bean
    PlatformAdminGrantTransaction platformAdminGrantTransaction(JdbcTemplate jdbc,
            IdentityRepository identities, PlatformRoleAssignmentRepository roles,
            OutboxEventRepository outbox, PlatformAdminGrantedEventFactory events,
            PlatformAdminSetupCaller caller, Clock clock) {
        return new PlatformAdminGrantTransaction(jdbc, identities, roles, outbox, events, caller, clock);
    }

    @Bean
    PlatformAdminGrantService platformAdminGrantService(PlatformAdminGrantTransaction transaction,
            PasswordSetupDeliveryService deliveries) {
        return new PlatformAdminGrantService(transaction, deliveries);
    }

    @Bean
    PlatformAdminDeliveryWorker platformAdminDeliveryWorker(PlatformAdminGrantService grants) {
        return new PlatformAdminDeliveryWorker(grants);
    }

    @Bean
    PlatformAdminSetupCaller platformAdminSetupCaller(JdbcTemplate jdbc) {
        return new PlatformAdminSetupCaller(jdbc);
    }

    @Bean
    PlatformAdminQueries platformAdminQueries(JdbcTemplate jdbc) {
        return new PlatformAdminQueries(jdbc);
    }
}
