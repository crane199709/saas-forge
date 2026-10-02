package io.saas.forge.gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions.lb;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration(proxyBeanMethods = false)
class GatewayRouteConfiguration {

    @Bean
    RouterFunction<ServerResponse> gatewayRoutes(GatewayRouteCatalog catalog) {
        RouterFunction<ServerResponse> routes = null;
        for (GatewayRouteCatalog.Route route : catalog.routes()) {
            RouterFunction<ServerResponse> gatewayRoute = gatewayRoute(route);
            routes = routes == null ? gatewayRoute : routes.and(gatewayRoute);
        }
        if (routes == null) {
            throw new IllegalStateException("OpenAPI route whitelist must not be empty");
        }
        return routes;
    }

    private RouterFunction<ServerResponse> gatewayRoute(GatewayRouteCatalog.Route route) {
        var builder = route(route.operationId());
        if (route.method() == HttpMethod.GET) {
            builder.GET(route.path(), http());
        } else if (route.method() == HttpMethod.POST) {
            builder.POST(route.path(), http());
        } else if (route.method() == HttpMethod.PUT) {
            builder.PUT(route.path(), http());
        } else if (route.method() == HttpMethod.DELETE) {
            builder.DELETE(route.path(), http());
        } else {
            throw new IllegalStateException("Unsupported OpenAPI HTTP method: " + route.method());
        }
        return builder.filter(lb(route.serviceId())).build();
    }
}
