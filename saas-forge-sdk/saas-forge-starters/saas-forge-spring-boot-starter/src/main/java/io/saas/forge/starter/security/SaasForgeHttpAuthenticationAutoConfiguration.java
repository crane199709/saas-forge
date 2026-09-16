package io.saas.forge.starter.security;

import io.saas.forge.contracts.route.HttpRouteCatalog;
import io.saas.forge.contracts.route.HttpRouteCatalogLoader;
import io.saas.forge.sdk.auth.IdentityContextAccessor;
import io.saas.forge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.ServiceContextAccessor;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import io.saas.forge.sdk.tenant.TenantContextAccessor;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/** 为 Servlet 接收端装配共享 Catalog 驱动的 User/Service Token 复验边界。 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.cloud.loadbalancer.config.BlockingLoadBalancerClientAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SaasForgeHttpAuthenticationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HttpRouteCatalog saasForgeHttpRouteCatalog() {
        return HttpRouteCatalogLoader.load();
    }

    @Bean
    ReceiverRouteCatalog saasForgeReceiverRouteCatalog(HttpRouteCatalog catalog, Environment environment) {
        return new ReceiverRouteCatalog(catalog, environment.getProperty("spring.application.name"));
    }

    @Bean
    ReceiverTokenAuthenticators saasForgeReceiverTokenAuthenticators(
            ObjectProvider<UserAccessTokenSignatureVerifier> userSignatures,
            ObjectProvider<UserAccessTokenContextRevocationChecker> userRevocations,
            ObjectProvider<ServiceAccessTokenSignatureVerifier> serviceSignatures,
            ObjectProvider<ServiceAccessTokenRevocationChecker> serviceRevocations) {
        return new ReceiverTokenAuthenticators(
                required(userSignatures, UserAccessTokenSignatureVerifier.class)::verify,
                required(userRevocations, UserAccessTokenContextRevocationChecker.class),
                required(serviceSignatures, ServiceAccessTokenSignatureVerifier.class)::verify,
                required(serviceRevocations, ServiceAccessTokenRevocationChecker.class));
    }

    @Bean
    ReceiverProblemDetailsWriter saasForgeReceiverProblemDetailsWriter(ObjectMapper objectMapper) {
        return new ReceiverProblemDetailsWriter(objectMapper);
    }

    @Bean
    IdentityContextAccessor saasForgeIdentityContextAccessor() {
        return new SpringSecurityIdentityContextAccessor();
    }

    @Bean
    ServiceContextAccessor saasForgeServiceContextAccessor() {
        return new SpringSecurityServiceContextAccessor();
    }

    @Bean
    TenantContextAccessor saasForgeTenantContextAccessor() {
        return new SpringSecurityTenantContextAccessor();
    }

    @Bean
    FilterRegistrationBean<HttpReceiverAuthenticationFilter> saasForgeHttpReceiverAuthenticationFilter(
            ReceiverRouteCatalog catalog,
            ReceiverTokenAuthenticators authenticators,
            ReceiverProblemDetailsWriter problems, Environment environment) {
        var registration = new FilterRegistrationBean<>(
                new HttpReceiverAuthenticationFilter(catalog, authenticators, problems,
                        environment.getProperty("saas.forge.authentication.max-json-bytes", Integer.class, 1024 * 1024)));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean({UserAccessTokenSignatureVerifier.class, ServiceAccessTokenSignatureVerifier.class,
            UserAccessTokenContextRevocationChecker.class, ServiceAccessTokenRevocationChecker.class})
    static class DefaultAuthentication {
        @Bean
        IamJwksKeyResolver saasForgeIamJwks(LoadBalancerClient discovery,
                Environment environment) {
            var binder = Binder.get(environment);
            return new IamJwksKeyResolver(discovery, Clock.systemUTC(),
                    binder.bind("saas.forge.authentication.jwks.refresh-interval", Duration.class)
                            .orElse(Duration.ofSeconds(10)),
                    binder.bind("saas.forge.authentication.jwks.wait-timeout", Duration.class)
                            .orElse(Duration.ofSeconds(2)));
        }

        @Bean
        UserAccessTokenSignatureVerifier saasForgeUserSignatures(IamJwksKeyResolver keys, Environment environment) {
            return new UserAccessTokenSignatureVerifier(keys, Clock.systemUTC(),
                    environment.getRequiredProperty("security.jwt.issuer"), "saas.forge-api", Duration.ofSeconds(30),
                    environment.getProperty("security.browser.console-enabled", Boolean.class, false));
        }

        @Bean
        ServiceAccessTokenSignatureVerifier saasForgeServiceSignatures(IamJwksKeyResolver keys, Environment environment) {
            return new ServiceAccessTokenSignatureVerifier(keys, Clock.systemUTC(),
                    environment.getRequiredProperty("security.jwt.issuer"), "saas.forge-api", Duration.ofSeconds(30));
        }

        @Bean
        RedisReceiverTokenRevocationChecker saasForgeRedisRevocations(
                StringRedisTemplate redis, Environment environment) {
            return new RedisReceiverTokenRevocationChecker(redis, environment.getRequiredProperty("saas.forge.environment"));
        }

        @Bean(name = AuthenticationReadiness.NAME)
        AuthenticationReadiness saasForgeAuthenticationReadiness(IamJwksKeyResolver keys,
                RedisReceiverTokenRevocationChecker revocations) {
            return new AuthenticationReadiness(keys, revocations);
        }

        @Bean
        UserAccessTokenContextRevocationChecker saasForgeUserRevocations(RedisReceiverTokenRevocationChecker revocations) {
            return revocations::isUserTokenRevoked;
        }

        @Bean
        ServiceAccessTokenRevocationChecker saasForgeServiceRevocations(RedisReceiverTokenRevocationChecker revocations) {
            return revocations::isServiceTokenRevoked;
        }
    }

    private static <T> T required(ObjectProvider<T> provider, Class<T> adapterType) {
        T adapter = provider.getIfAvailable();
        if (adapter == null) {
            throw new IllegalStateException("缺少必需认证适配器: " + adapterType.getName());
        }
        return adapter;
    }
}
