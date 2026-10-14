package io.saas.forge.contracts.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpRouteCatalogLoaderTest {

    @Test
    void loadsThePublishedCatalog() {
        HttpRouteCatalog catalog = HttpRouteCatalogLoader.load();
        long acceptanceRoutes = catalog.routes().stream()
                .filter(route -> route.path().startsWith("/__test/"))
                .count();

        assertEquals(HttpRouteCatalogLoader.SUPPORTED_SCHEMA_VERSION, catalog.schemaVersion());
        // #225 匿名 Password Setup、#226 角色管理、#228 成员禁用、#227 组织归属和 #229 成员启用操作均计入目录。
        assertEquals(121 + acceptanceRoutes, catalog.routes().size());
        var setup = catalog.routes().stream()
                .filter(route -> route.operationId().equals("requestInvitationPasswordSetup"))
                .findFirst().orElseThrow();
        assertEquals("tenant-access-service", setup.serviceId());
        assertEquals(HttpRouteCatalog.CredentialRequirement.ANONYMOUS, setup.credentialRequirement());
        var clients = catalog.routes().stream().filter(route -> route.operationId().equals("listOAuthClients"))
                .findFirst().orElseThrow();
        assertEquals("iam-service", clients.serviceId());
        assertEquals(HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, clients.credentialRequirement());
        for (String operation : java.util.List.of("getTenantLifecycle", "continueTenantLifecycle", "getTenantAdministratorPasswordSetup", "recoverTenantAdministratorPasswordSetup", "getTenantAdministratorInitialization", "recoverTenantAdministratorInitialization", "listPermissions", "decidePermission", "updatePlatformTenant", "listInvitations", "createInvitation", "resendInvitation", "revokeInvitation", "listTenantRoles", "createTenantRole", "getTenantRole", "updateTenantRole", "deleteTenantRole")) {
            var route = catalog.routes().stream().filter(value -> value.operationId().equals(operation)).findFirst().orElseThrow();
            assertEquals("tenant-access-service", route.serviceId());
            assertEquals(HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, route.credentialRequirement());
        }
        for (String operation : java.util.List.of("listOrganizationUnits", "createOrganizationUnit",
                "renameOrganizationUnit", "moveOrganizationUnit", "deleteOrganizationUnit",
                "listMembershipOrganizationUnits", "addMembershipOrganizationUnit",
                "removeMembershipOrganizationUnit", "listMembershipRoles", "assignMembershipRole",
                "revokeMembershipRole", "readMembershipDisable", "disableMembership",
                "recoverMembershipDisable", "readMembershipEnable", "enableMembership")) {
            var route = catalog.routes().stream().filter(value -> value.operationId().equals(operation))
                    .findFirst().orElseThrow();
            assertEquals("tenant-access-service", route.serviceId());
            assertEquals(HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, route.credentialRequirement());
        }
        for (String operation : java.util.List.of("listOAuthClientOperations", "recoverOAuthClientOperation", "getOAuthClientCredentialStatus")) {
            var route = catalog.routes().stream().filter(value -> value.operationId().equals(operation)).findFirst().orElseThrow();
            assertEquals("iam-service", route.serviceId());
            assertEquals(HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, route.credentialRequirement());
        }
        for (String operation : java.util.List.of("listPlatformAdministrators", "grantPlatformAdministrator",
                "revokePlatformAdministrator")) {
            var route = catalog.routes().stream().filter(value -> value.operationId().equals(operation))
                    .findFirst().orElseThrow();
            assertEquals("iam-service", route.serviceId());
            assertEquals(HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, route.credentialRequirement());
        }
        for (String operation : java.util.List.of("listQuotaDefinitions", "getQuotaDefinition",
                "listQuotaDefinitionOperations", "getQuotaDefinitionOperation", "recoverQuotaDefinitionOperation", "listPlans", "getPlan", "listPlanOperations", "getPlanOperation", "recoverPlanOperation")) {
            var route = catalog.routes().stream().filter(value -> value.operationId().equals(operation)).findFirst().orElseThrow();
            assertEquals("entitlement-service", route.serviceId());
            assertEquals(HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, route.credentialRequirement());
        }
    }

    @Test
    void rejectsUnsupportedMissingUnknownAndEmptyCatalogData() {
        assertInvalid("{\"schemaVersion\":2,\"routes\":[]}");
        assertInvalid("{\"routes\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"routes\":[],\"unknown\":true}");
        assertInvalid("{\"schemaVersion\":1,\"routes\":[]}");
        assertInvalid("{\"schemaVersion\":1,\"routes\":[{\"operationId\":\"bad\",\"method\":\"GET\","
                + "\"path\":\"not-absolute\",\"serviceId\":\"iam-service\","
                + "\"credentialRequirement\":\"ANONYMOUS\",\"requiredScopes\":[]}]}");
    }

    private void assertInvalid(String json) {
        var input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> HttpRouteCatalogLoader.load(input));
    }
}
