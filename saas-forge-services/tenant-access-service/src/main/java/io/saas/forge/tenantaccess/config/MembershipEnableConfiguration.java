package io.saas.forge.tenantaccess.config;

import io.grpc.Channel;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saas.forge.contracts.iam.session.v1.UserSessionRevocationServiceGrpc;
import io.saas.forge.tenantaccess.application.authorization.PermissionAuthority;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableFenceGateway;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableQuotaGateway;
import io.saas.forge.tenantaccess.application.membership.MembershipEnableService;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcMembershipEnableFenceGateway;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcMembershipEnableQuotaGateway;
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
public class MembershipEnableConfiguration {
    @Bean
    MembershipEnableQuotaGateway membershipEnableQuotaGateway(@Qualifier("entitlementServiceChannel") Channel channel,
            IamServiceAccessTokenProvider tokens) {
        return new GrpcMembershipEnableQuotaGateway(QuotaCommandServiceGrpc.newBlockingStub(channel),
                tokens::quotaWriteToken);
    }

    @Bean
    MembershipEnableFenceGateway membershipEnableFenceGateway(@Qualifier("iamServiceChannel") Channel channel,
            IamServiceAccessTokenProvider tokens) {
        return new GrpcMembershipEnableFenceGateway(UserSessionRevocationServiceGrpc.newBlockingStub(channel),
                tokens::sessionWriteToken);
    }

    @Bean
    MembershipEnableService membershipEnableService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            PermissionAuthority permissions, MembershipEnableQuotaGateway quota,
            MembershipEnableFenceGateway fences, OutboxEventRepository outbox, ObjectMapper json,
            UuidV7Generator ids, Clock clock,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new MembershipEnableService(jdbc, manager, permissions, quota, fences, outbox, json, ids, clock, topic);
    }

    @Bean
    MembershipEnableWorker membershipEnableWorker(MembershipEnableService service) {
        return new MembershipEnableWorker(service);
    }

    public static final class MembershipEnableWorker {
        private final MembershipEnableService service;
        MembershipEnableWorker(MembershipEnableService service) { this.service = service; }
        @Scheduled(fixedDelayString = "${saas.forge.tenant-access.lifecycle.recovery-delay:PT1S}")
        public void recoverNext() { service.recoverNext(); }
    }
}
