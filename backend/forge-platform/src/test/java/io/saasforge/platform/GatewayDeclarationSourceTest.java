package io.saasforge.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.nacos.api.config.ConfigService;
import io.saasforge.platform.onboarding.model.OnboardingDiagnostics.ConfigurationEvidence;
import io.saasforge.platform.onboarding.service.GatewayDeclarationSource;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GatewayDeclarationSourceTest {
    private static final String ROOT_ROUTE = """
            - uri: lb://reports-service
              predicates: [Path=/reports/**]
              filters: [StripPrefix=1]
            """;
    private static final String CATALOG_ROUTE = """
            - uri: lb://reports-service
              predicates: [Path=/reports-platform/**]
              filters: [StripPrefix=1, PrefixPath=/platform]
            """;
    private static final String REALMS = """
            - {prefix: /reports, realm: tenant}
            - {prefix: /reports-platform, realm: platform}
            """;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distinctRealmRoutesKeepRootOpenApiProbeRegardlessOfOrder(boolean catalogFirst) {
        var evidence = read(catalogFirst ? CATALOG_ROUTE + ROOT_ROUTE : ROOT_ROUTE + CATALOG_ROUTE, REALMS);
        assertThat(evidence.status()).isEqualTo("CONFIGURED");
        assertThat(evidence.prefix()).isEqualTo("/reports");
        assertThat(evidence.missing()).isEmpty();
    }

    @Test
    void expandedRootRouteCanSupplyOpenApiProbe() {
        String root = """
                - uri: lb://reports-service
                  predicates:
                    - name: Path
                      args: {_genkey_0: /reports/**}
                  filters:
                    - name: StripPrefix
                      args: {parts: 1}
                """;
        var evidence = read(CATALOG_ROUTE + root, REALMS);
        assertThat(evidence.status()).isEqualTo("CONFIGURED");
        assertThat(evidence.prefix()).isEqualTo("/reports");
    }

    @Test
    void duplicatePrefixRemainsAmbiguous() {
        var evidence = read(ROOT_ROUTE + ROOT_ROUTE, REALMS);
        assertThat(evidence.status()).isEqualTo("INCOMPLETE");
        assertThat(evidence.prefix()).isNull();
        assertThat(evidence.missing()).contains("MULTIPLE_ROUTES");
    }

    @Test
    void rootProbeDoesNotHideMissingRealmOnAnotherRoute() {
        var evidence = read(ROOT_ROUTE + CATALOG_ROUTE, "- {prefix: /reports, realm: tenant}\n");
        assertThat(evidence.status()).isEqualTo("INCOMPLETE");
        assertThat(evidence.prefix()).isEqualTo("/reports");
        assertThat(evidence.missing()).containsExactly("AUTH_PREFIX_MISSING");
    }

    @Test
    void rootProbeDoesNotHideUnrecognizedPathOnAnotherRoute() {
        var evidence = read(ROOT_ROUTE + CATALOG_ROUTE.replace("/reports-platform/**", "/reports/platform/**"), REALMS);
        assertThat(evidence.status()).isEqualTo("INCOMPLETE");
        assertThat(evidence.prefix()).isEqualTo("/reports");
        assertThat(evidence.missing()).containsExactly("ROUTE_PREFIX_MISSING");
    }

    @Test
    void rewrittenRoutesWithoutRootOpenApiRemainUnconfirmed() {
        var evidence =
                read(ROOT_ROUTE.replace("StripPrefix=1", "StripPrefix=1, PrefixPath=/tenant") + CATALOG_ROUTE, REALMS);
        assertThat(evidence.status()).isEqualTo("INCOMPLETE");
        assertThat(evidence.prefix()).isNull();
        assertThat(evidence.missing()).contains("MULTIPLE_ROUTES");
    }

    private ConfigurationEvidence read(String routes, String realms) {
        String yaml = "spring:\n  cloud:\n    gateway:\n      server:\n        webflux:\n          routes:\n"
                + routes.indent(12)
                + "forge:\n  gateway:\n    services:\n"
                + realms.indent(6);
        ConfigService nacos = (ConfigService) Proxy.newProxyInstance(
                ConfigService.class.getClassLoader(),
                new Class<?>[] {ConfigService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServerStatus" -> "UP";
                    case "getConfig" -> yaml;
                    default -> null;
                });
        return new GatewayDeclarationSource(nacos, "DEFAULT_GROUP").read().forService("reports-service");
    }
}
