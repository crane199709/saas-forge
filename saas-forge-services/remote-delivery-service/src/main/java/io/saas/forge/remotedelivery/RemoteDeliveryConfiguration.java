package io.saas.forge.remotedelivery;

import io.grpc.Channel;
import io.saas.forge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import io.saas.forge.observability.HttpTraceFilter;
import io.saas.forge.sdk.auth.PlatformRoleChecker;
import io.opentelemetry.api.OpenTelemetry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
class RemoteDeliveryConfiguration {
    @Bean org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer strictTextFields() {
        return builder -> builder.withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config -> {
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Integer, tools.jackson.databind.cfg.CoercionAction.Fail);
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Float, tools.jackson.databind.cfg.CoercionAction.Fail);
            config.setCoercion(tools.jackson.databind.cfg.CoercionInputShape.Boolean, tools.jackson.databind.cfg.CoercionAction.Fail);
        });
    }
    @Bean Clock remoteDeliveryClock() { return Clock.systemUTC(); }
    @Bean ManifestPolicy manifestPolicy(Environment environment) {
        Map<String,UUID> clients = Binder.get(environment).bind("saas.forge.remote-delivery.module-clients",Bindable.mapOf(String.class,UUID.class)).orElse(Map.of());
        return new ManifestPolicy(environment.getRequiredProperty("browser.rootDomain"),clients);
    }
    @Bean IamServiceAccessTokenProvider serviceTokens(RestClient remoteDeliveryIamRestClient,Clock clock,
            @Value("${saas.forge.remote-delivery.service-client-id-file}") Path id,
            @Value("${saas.forge.remote-delivery.service-client-secret-file}") Path secret) {
        return new IamServiceAccessTokenProvider(remoteDeliveryIamRestClient,id,secret,clock);
    }
    @Bean PlatformRoleChecker roles(@Qualifier("iamServiceChannel") Channel channel,IamServiceAccessTokenProvider tokens) {
        return new GrpcPlatformRoleChecker(PlatformAuthorizationServiceGrpc.newBlockingStub(channel),tokens::token);
    }
    @Bean GrpcTenantContextChecker tenantContextChecker(@Qualifier("tenantAccessServiceChannel") Channel channel,
            IamServiceAccessTokenProvider tokens) {
        return new GrpcTenantContextChecker(
                io.saas.forge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc.newBlockingStub(channel),
                tokens::membershipReadToken);
    }
    @Bean FilterRegistrationBean<HttpTraceFilter> remoteDeliveryTrace(OpenTelemetry telemetry,
            io.saas.forge.contracts.route.HttpRouteCatalog catalog) {
        var matcher = new org.springframework.util.AntPathMatcher();
        var routes = catalog.routes().stream().filter(route -> route.serviceId().equals("remote-delivery-service")).toList();
        var filter = new HttpTraceFilter(telemetry, request -> routes.stream()
                .filter(route -> route.method().name().equals(request.getMethod()) && matcher.match(route.path(), request.getRequestURI()))
                .map(io.saas.forge.contracts.route.HttpRouteCatalog.Route::path).findFirst().orElse("/unmatched"));
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);return registration;
    }
}
