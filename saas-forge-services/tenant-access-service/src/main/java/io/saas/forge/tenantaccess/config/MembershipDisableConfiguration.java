package io.saas.forge.tenantaccess.config;

import io.grpc.Channel;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.membership.MembershipDisableService;
import io.saas.forge.tenantaccess.application.membership.MembershipQuotaGateway;
import io.saas.forge.tenantaccess.application.membership.MembershipRevocationGateway;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcMembershipQuotaGateway;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcMembershipRevocationGateway;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class MembershipDisableConfiguration {
    @Bean
    MembershipRevocationGateway membershipRevocationGateway(@Qualifier("iamServiceChannel") Channel channel,
            IamServiceAccessTokenProvider tokens) {
        return new GrpcMembershipRevocationGateway(UserSessionRevocationServiceGrpc.newBlockingStub(channel),
                tokens::sessionWriteToken);
    }

    @Bean
    MembershipQuotaGateway membershipQuotaGateway(@Qualifier("entitlementServiceChannel") Channel channel,
            IamServiceAccessTokenProvider tokens) {
        return new GrpcMembershipQuotaGateway(QuotaCommandServiceGrpc.newBlockingStub(channel),
                tokens::quotaWriteToken);
    }

    @Bean
    MembershipDisableService membershipDisableService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            PermissionAuthority permissions, MembershipRevocationGateway revocations, MembershipQuotaGateway quota,
            OutboxEventRepository outbox, ObjectMapper json, UuidV7Generator ids, Clock clock,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new MembershipDisableService(jdbc, manager, permissions, revocations, quota,
                outbox, json, ids, clock, topic);
    }

    @Bean
    MembershipDisableWorker membershipDisableWorker(MembershipDisableService service) {
        return new MembershipDisableWorker(service);
    }

    public static final class MembershipDisableWorker {
        private final MembershipDisableService service;
        MembershipDisableWorker(MembershipDisableService service) { this.service = service; }
        @Scheduled(fixedDelayString = "${saas.forge.tenant-access.lifecycle.recovery-delay:PT1S}")
        public void recoverNext() { service.recoverNext(); }
    }
}
