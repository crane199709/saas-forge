package io.saas.forge.tenantaccess.config;

import io.saas.forge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saas.forge.tenantaccess.application.invitation.InvitationEventFactory;
import io.saas.forge.tenantaccess.application.invitation.InvitationDeliveryException;
import io.saas.forge.tenantaccess.application.invitation.InvitationMailer;
import io.saas.forge.tenantaccess.application.invitation.InvitationRepository;
import io.saas.forge.tenantaccess.application.invitation.InvitationService;
import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceRepository;
import io.saas.forge.tenantaccess.application.invitation.InvitationAcceptanceService;
import io.saas.forge.tenantaccess.application.invitation.InvitationQuotaGateway;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcInvitationQuotaGateway;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import io.saas.forge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.grpc.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import io.saas.forge.tenantaccess.application.tenant.UuidV7Generator;
import io.saas.forge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class InvitationConfiguration {
    @Bean
    InvitationQuotaGateway invitationQuotaGateway(
            @Qualifier("entitlementServiceChannel") Channel channel, IamServiceAccessTokenProvider tokens) {
        return new GrpcInvitationQuotaGateway(QuotaCommandServiceGrpc.newBlockingStub(channel),
                tokens::quotaWriteToken);
    }

    @Bean
    InvitationAcceptanceService invitationAcceptanceService(InvitationAcceptanceRepository repository,
            InvitationQuotaGateway quota, OutboxEventRepository outbox, InvitationEventFactory events,
            PlatformTransactionManager manager, UuidV7Generator ids, Clock clock) {
        return new InvitationAcceptanceService(repository, quota, outbox, events,
                new TransactionTemplate(manager), ids, clock);
    }
    @Bean
    InvitationEventFactory invitationEventFactory(ObjectMapper json, UuidV7Generator ids,
            @Value("${saas.forge.tenant-access.outbox-topic}") String topic) {
        return new InvitationEventFactory(json, ids, topic);
    }

    @Bean
    InvitationService invitationService(InvitationRepository invitations, IdentityProvisioningGateway identities,
            OutboxEventRepository outbox, InvitationEventFactory events, InvitationMailer mailer,
            PlatformTransactionManager transactionManager, UuidV7Generator ids, Clock clock) {
        return new InvitationService(invitations, identities, outbox, events, mailer,
                new TransactionTemplate(transactionManager), ids, new SecureRandom(), clock);
    }

    @Bean
    InvitationMailer invitationMailer(
            @Value("${saas.forge.environment:dev}") String environment,
            @Value("${saas.forge.tenant-access.invitation.smtp.host:}") String host,
            @Value("${saas.forge.tenant-access.invitation.smtp.port:1025}") int port,
            @Value("${saas.forge.tenant-access.invitation.smtp.username:}") String username,
            @Value("${saas.forge.tenant-access.invitation.smtp.password:}") String password,
            @Value("${saas.forge.tenant-access.invitation.smtp.from:}") String from,
            @Value("${saas.forge.tenant-access.invitation.smtp.starttls:false}") boolean startTls,
            @Value("${saas.forge.tenant-access.invitation.page-uri:}") String pageUri) {
        boolean production = "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
        if (production && (host.isBlank() || username.isBlank() || password.isBlank()
                || from.isBlank() || pageUri.isBlank() || !startTls)) {
            throw new IllegalStateException("生产 Invitation SMTP 配置不完整");
        }
        if (host.isBlank()) return (recipient, token) -> {
            throw new InvitationDeliveryException();
        };
        if (from.isBlank() || port < 1 || port > 65535) {
            throw new IllegalStateException("Invitation SMTP 配置不合法");
        }
        URI page = URI.create(pageUri.isBlank()
                ? "https://console.saas.forge.test/invitation" : pageUri);
        if (!"https".equals(page.getScheme()) || page.getHost() == null
                || page.getQuery() != null || page.getFragment() != null) {
            throw new IllegalStateException("Invitation 页面必须是受信 HTTPS 地址");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        if (!username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        Properties properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(startTls));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(startTls));
        return (recipient, token) -> {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(recipient);
            message.setSubject("SaaS Forge Tenant 邀请");
            message.setText("请在 7 天内使用以下链接继续加入 Tenant：\n\n"
                    + page + "#token=" + token + "\n\n如果你没有预期收到邀请，请忽略本邮件。");
            try {
                sender.send(message);
            } catch (MailException exception) {
                throw new InvitationDeliveryException(exception);
            }
        };
    }
}
