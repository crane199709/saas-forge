package io.saas.forge.iambootstrap;

import io.saas.forge.iam.application.bootstrap.ReservedServiceClientBootstrapService;
import io.saas.forge.iam.domain.client.OAuthClientRepository;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReservedServiceClientBootstrapConfiguration {
    @Bean
    Clock reservedClientBootstrapClock() {
        return Clock.systemUTC();
    }

    @Bean
    ReservedServiceClientBootstrapService reservedServiceClientBootstrapService(
            OAuthClientRepository clients, Clock reservedClientBootstrapClock) {
        return new ReservedServiceClientBootstrapService(clients, reservedClientBootstrapClock);
    }

    @Bean
    SecretTextFileReader reservedClientSecretTextFileReader() {
        return new SecretTextFileReader();
    }

    @Bean
    ReservedServiceClientBootstrapRunner reservedServiceClientBootstrapRunner(
            ReservedServiceClientBootstrapService service,
            SecretTextFileReader reader,
            @Value("${saas.forge.iam.bootstrap.service-clients.iam.id-file}") Path iamIdFile,
            @Value("${saas.forge.iam.bootstrap.service-clients.iam.secret-file}") Path iamSecretFile,
            @Value("${saas.forge.iam.bootstrap.service-clients.tenant-access.id-file}") Path tenantAccessIdFile,
            @Value("${saas.forge.iam.bootstrap.service-clients.tenant-access.secret-file}") Path tenantAccessSecretFile,
            @Value("${saas.forge.iam.bootstrap.service-clients.entitlement.id-file}") Path entitlementIdFile,
            @Value("${saas.forge.iam.bootstrap.service-clients.entitlement.secret-file}") Path entitlementSecretFile,
            @Value("${saas.forge.iam.bootstrap.service-clients.remote-delivery.id-file:}") String remoteDeliveryIdFile,
            @Value("${saas.forge.iam.bootstrap.service-clients.remote-delivery.secret-file:}") String remoteDeliverySecretFile) {
        return new ReservedServiceClientBootstrapRunner(
                service, reader,
                iamIdFile, iamSecretFile,
                tenantAccessIdFile, tenantAccessSecretFile,
                entitlementIdFile, entitlementSecretFile,
                remoteDeliveryIdFile.isBlank() ? null : Path.of(remoteDeliveryIdFile),
                remoteDeliverySecretFile.isBlank() ? null : Path.of(remoteDeliverySecretFile));
    }
}
