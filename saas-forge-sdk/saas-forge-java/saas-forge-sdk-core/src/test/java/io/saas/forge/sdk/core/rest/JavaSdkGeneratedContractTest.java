package io.saas.forge.sdk.core.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JavaSdkGeneratedContractTest {

    private static final Path GENERATED_CONTRACT = Path.of("target/generated-openapi/java-sdk-v1.yaml")
            .toAbsolutePath()
            .normalize();
    private static final Set<String> APPROVED_OPERATIONS = Set.of(
            "getCurrentTenantContext",
            "issueClientCredentialsToken",
            "getJwks",
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
    private static final Set<String> BROWSER_OPERATIONS = Set.of(
            "login",
            "refreshAccessToken",
            "changeInitialPassword",
            "establishPassword",
            "selectAuthenticationContext",
            "logout",
            "switchTenantContext");

    @Test
    void derivesOnlyTheApprovedJavaSdkContractInsideTheBuildDirectory() throws IOException {
        assertTrue(Files.isRegularFile(GENERATED_CONTRACT),
                "Maven 必须在 target 中派生 Java SDK OpenAPI 视图");
        String generated = Files.readString(GENERATED_CONTRACT);

        APPROVED_OPERATIONS.forEach(operationId ->
                assertTrue(generated.contains("operationId: " + operationId), operationId));
        BROWSER_OPERATIONS.forEach(operationId ->
                assertFalse(generated.contains("operationId: " + operationId), operationId));

        assertTrue(generated.contains("x-saas.forge-service: iam-service"));
        assertTrue(generated.contains("security: [{ UserBearerAuth: [] }]"));
        assertFalse(generated.contains("BrowserOrigin:"));
        assertFalse(generated.contains("FetchSite:"));
        assertFalse(generated.contains("RefreshTokenCookie"));
        assertTrue(generated.lines().anyMatch("    InvitationPasswordSetupRequest:"::equals));
        assertFalse(generated.lines().anyMatch("    LoginRequest:"::equals));
        assertFalse(generated.lines().anyMatch("    PasswordSetupRequest:"::equals));
        assertFalse(generated.lines().anyMatch("    SessionSlotRequest:"::equals));
    }
}
