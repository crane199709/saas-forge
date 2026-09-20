package io.saas.forge.contracts.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class UnifiedConsoleContractTest {
    @Test
    void exposesVersionedConsoleOperationsWithoutReplacingLegacyBusinessRoutes() {
        var catalog = HttpRouteCatalogLoader.load();
        var operations = catalog.routes().stream()
                .filter(route -> route.path().startsWith("/api/v2/auth/"))
                .peek(route -> assertEquals("iam-service", route.serviceId()))
                .map(HttpRouteCatalog.Route::operationId).collect(Collectors.toSet());
        assertEquals(Set.of("bootstrapConsoleSession", "loginConsoleSession", "getConsoleSession",
                "getAvailableWorkContexts", "refreshConsoleSession", "selectConsoleContext",
                "logoutConsoleSession", "changeConsoleInitialPassword"), operations);
        assertEquals(1, catalog.routes().stream()
                .filter(route -> route.operationId().equals("listOAuthClients"))
                .filter(route -> route.credentialRequirement() == HttpRouteCatalog.CredentialRequirement.USER_REQUIRED)
                .count());
    }
}
