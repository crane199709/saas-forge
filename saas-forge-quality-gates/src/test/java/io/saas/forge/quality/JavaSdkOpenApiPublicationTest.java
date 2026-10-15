package io.saas.forge.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class JavaSdkOpenApiPublicationTest {

    private static final Path OPENAPI = Path.of(System.getProperty("repositoryRoot"))
            .resolve("saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml");
    private static final String PUBLICATION_MARKER = "x-saas.forge-java-sdk";
    private static final Set<String> APPROVED_OPERATIONS = Set.of(
            "getCurrentTenantContext",
            "issueClientCredentialsToken",
            "getJwks",
            "listPermissions",
            "listEffectivePermissions",
            "decidePermission",
            "listMembers",
            "readMembershipDisable",
            "disableMembership",
            "recoverMembershipDisable",
            "readMembershipEnable",
            "enableMembership",
            "listInvitations",
            "createInvitation",
            "requestInvitationPasswordSetup",
            "resendInvitation",
            "revokeInvitation",
            "updatePlatformTenant",
            "closeTenant",
            "recoverTenantClosure",
            "createPlatformTenant",
            "initializeTenantAdministrator",
            "resendTenantAdministratorPasswordSetup",
            "suspendTenant",
            "resumeTenant",
            "recoverTenantSuspension",
            "createQuotaDefinition",
            "activateQuotaDefinition",
            "createPlan",
            "activatePlan",
            "createInitialSubscription",
            "createOAuthClient",
            "getOAuthClient",
            "rotateOAuthClientSecret",
            "recoverOAuthClientSecret",
            "revokeOAuthClient");

    @Test
    void publishesOnlyTheExplicitlyApprovedServerSideOperations() throws IOException {
        Map<String, Object> document = readOpenApi();
        Set<String> published = new LinkedHashSet<>();

        for (Map.Entry<String, Object> path : map(document.get("paths")).entrySet()) {
            for (Map.Entry<String, Object> method : map(path.getValue()).entrySet()) {
                Map<String, Object> operation = map(method.getValue());
                Object marker = operation.get(PUBLICATION_MARKER);
                if (marker == null) {
                    continue;
                }
                assertEquals(Boolean.TRUE, marker,
                        path.getKey() + " 的 " + PUBLICATION_MARKER + " 只允许布尔值 true");
                assertTrue(published.add((String) operation.get("operationId")),
                        "Java SDK operationId 不得重复");
            }
        }

        assertEquals(APPROVED_OPERATIONS, published,
                "Java SDK 只能发布显式评审通过且不依赖浏览器安全元数据的 operation");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readOpenApi() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options)).load(Files.readString(OPENAPI));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
