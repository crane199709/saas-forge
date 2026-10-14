package io.saas.forge.tenantaccess.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.saas.forge.sdk.auth.PlatformAuthorizationDeniedException;
import io.saas.forge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saas.forge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializationResult;
import io.saas.forge.tenantaccess.application.administrator.TenantAdministratorInitializationException;
import io.saas.forge.tenantaccess.application.administrator.AdministratorPasswordSetupException;
import io.saas.forge.tenantaccess.application.administrator.ResendAdministratorPasswordSetupService;
import io.saas.forge.tenantaccess.domain.tenant.TenantStatus;
import io.saas.forge.tenantaccess.application.tenant.RecoverableTenantCreationService;
import io.saas.forge.tenantaccess.application.tenant.TenantCreationResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TenantCreationControllerTest {
    private static final UUID KEY = UUID.fromString("019535d9-0000-7000-8000-000000000001");

    @Test
    void updateTenantRequiresCurrentPlatformAuthority() throws Exception {
        MockMvc mvc = mvc(authorization -> { throw new PlatformAuthorizationDeniedException(); }, unusedCreation());

        mvc.perform(put("/api/v1/platform/tenants/{tenantId}", KEY)
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"New name\",\"expiresAt\":null}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHORIZATION_DENIED"));
    }

    @Test
    void everyTenantReadAndRecoveryRechecksCurrentPlatformAuthorization() throws Exception {
        MockMvc mvc = mvc(authorization -> { throw new PlatformAuthorizationDeniedException(); }, unusedCreation());
        for (var request : java.util.List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/tenants"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/tenants/" + KEY),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/tenant-creations"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/tenant-creations/" + KEY),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/platform/tenants/" + KEY + "/lifecycle"),
                post("/api/v1/platform/tenants/" + KEY + "/lifecycle-operations/" + KEY + "/continuations"),
                post("/api/v1/platform/tenant-creations/" + KEY + "/recovery").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))) {
            mvc.perform(request.header("Authorization", "Bearer platform-token"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("PLATFORM_AUTHORIZATION_DENIED"));
        }
    }

    @Test
    void lifecycleReadAndContinuationAreNoStoreAndUseThePublishedOperationIdentity() throws Exception {
        var lifecycle = org.mockito.Mockito.mock(io.saas.forge.tenantaccess.application.tenant.TenantLifecycleService.class);
        var observed = new io.saas.forge.tenantaccess.application.tenant.TenantLifecycleProgress(
                KEY, KEY, "SUSPEND", "PENDING", false, false, false, true);
        org.mockito.Mockito.when(lifecycle.read(KEY)).thenReturn(observed);
        Instant at = Instant.parse("2026-09-14T00:00:00Z");
        org.mockito.Mockito.when(lifecycle.continueOperation(org.mockito.ArgumentMatchers.eq(KEY),
                org.mockito.ArgumentMatchers.eq(KEY), org.mockito.ArgumentMatchers.any())).thenReturn(
                new io.saas.forge.tenantaccess.application.tenant.TenantLifecycleResult(
                        KEY, "Example", TenantStatus.SUSPENDED, null, at, at));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TenantCreationController(
                authorization -> KEY, unusedCreation(), null, null, lifecycle, null))
                .setControllerAdvice(new TenantCreationExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/platform/tenants/{tenantId}/lifecycle", KEY).header("Authorization", "Bearer platform-token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.operationId").value(KEY.toString()))
                .andExpect(jsonPath("$.canResume").value(false));
        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/lifecycle-operations/{operationId}/continuations", KEY, KEY)
                        .header("Authorization", "Bearer platform-token").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    void rejectsRequestWhenIamDoesNotConfirmPlatformAdminRole() throws Exception {
        PlatformAdminAuthorizer authorizer = authorization -> {
            throw new PlatformAuthorizationDeniedException();
        };
        RecoverableTenantCreationService creation = unusedCreation();
        MockMvc mvc = mvc(authorizer, creation);

        mvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Acme\",\"expiresAt\":\"2026-08-24T01:00:00.000Z\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHORIZATION_DENIED"));
    }

    @Test
    void acceptsPublishedV1RequestWithoutExpiry() throws Exception {
        PlatformAdminAuthorizer authorizer = authorization -> KEY;
        Instant createdAt = Instant.parse("2026-08-23T01:00:00Z");
        RecoverableTenantCreationService creation = new RecoverableTenantCreationService(null, null, null) {
            @Override
            public TenantCreationResult create(
                    UUID callerIdentityId,
                    UUID idempotencyKey,
                    String displayName,
                    Instant expiresAt,
                    String traceId) {
                assertEquals(KEY, callerIdentityId);
                assertEquals(KEY, idempotencyKey);
                assertEquals("Acme", displayName);
                assertNull(expiresAt);
                return new TenantCreationResult(
                        KEY, displayName, TenantStatus.PENDING, null, createdAt, createdAt);
            }
        };
        MockMvc mvc = mvc(authorizer, creation);

        mvc.perform(post("/api/v1/platform/tenants")
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Acme\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void initializesAdministratorOnlyAfterPlatformAuthorization() throws Exception {
        UUID tenantId = UUID.fromString("019535d9-0000-7000-8000-000000000010");
        Instant expiry = Instant.parse("2026-08-24T01:00:00Z");
        InitializeTenantAdministratorService initialization = new InitializeTenantAdministratorService(
                null, null, null, null, null, null) {
            @Override
            public TenantAdministratorInitializationResult initialize(
                    UUID actorIdentityId,
                    UUID idempotencyKey,
                    UUID requestedTenantId,
                    String email,
                    String displayName,
                    String traceId) {
                return new TenantAdministratorInitializationResult(
                        requestedTenantId, "Acme", TenantStatus.ACTIVE, expiry,
                        Instant.parse("2026-08-23T01:00:00Z"), Instant.parse("2026-08-23T02:00:00Z"));
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new TenantCreationController(
                                authorization -> KEY, unusedCreation(), initialization, unusedPasswordSetup()))
                .setControllerAdvice(new TenantCreationExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-initializations", tenantId)
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"administratorEmail\":\"admin@example.com\",\"administratorDisplayName\":\"Admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenantId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void rejectsAdministratorInitializationWhenCurrentPlatformRoleIsMissing() throws Exception {
        PlatformAdminAuthorizer denied = authorization -> {
            throw new PlatformAuthorizationDeniedException();
        };
        MockMvc mvc = mvc(denied, unusedCreation());

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-initializations",
                        UUID.fromString("019535d9-0000-7000-8000-000000000010"))
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"administratorEmail\":\"admin@example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHORIZATION_DENIED"));
    }

    @Test
    void compensationInProgressReturnsServiceUnavailableWithRetryAfter() throws Exception {
        InitializeTenantAdministratorService initialization = new InitializeTenantAdministratorService(
                null, null, null, null, null, null) {
            @Override
            public TenantAdministratorInitializationResult initialize(
                    UUID actorIdentityId,
                    UUID idempotencyKey,
                    UUID tenantId,
                    String email,
                    String displayName,
                    String traceId) {
                throw new TenantAdministratorInitializationException(
                        "TENANT_ADMIN_INITIALIZATION_COMPENSATING", "正在补偿", 7);
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TenantCreationController(
                        authorization -> KEY, unusedCreation(), initialization, unusedPasswordSetup()))
                .setControllerAdvice(new TenantCreationExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-initializations",
                        UUID.fromString("019535d9-0000-7000-8000-000000000010"))
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"administratorEmail\":\"admin@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.code").value("TENANT_ADMIN_INITIALIZATION_COMPENSATING"));
    }

    @Test
    void resendsPasswordSetupWithoutRequestBodyOrSensitiveResponse() throws Exception {
        UUID tenantId = UUID.fromString("019535d9-0000-7000-8000-000000000010");
        ResendAdministratorPasswordSetupService passwordSetup = new ResendAdministratorPasswordSetupService(
                null, null, null, null, null, null) {
            @Override
            public void resend(UUID actorIdentityId, UUID idempotencyKey, UUID requestedTenantId, String traceId) {
                assertEquals(KEY, actorIdentityId);
                assertEquals(KEY, idempotencyKey);
                assertEquals(tenantId, requestedTenantId);
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TenantCreationController(
                        authorization -> KEY, unusedCreation(), unusedInitialization(), passwordSetup))
                .setControllerAdvice(new TenantCreationExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-password-setups", tenantId)
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void pendingPasswordSetupReturnsRetryAfterWithoutDeliveryInternals() throws Exception {
        ResendAdministratorPasswordSetupService passwordSetup = new ResendAdministratorPasswordSetupService(
                null, null, null, null, null, null) {
            @Override
            public void resend(UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String traceId) {
                throw new AdministratorPasswordSetupException(
                        "PASSWORD_SETUP_DELIVERY_PENDING", "投递尚未完成", 5);
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TenantCreationController(
                        authorization -> KEY, unusedCreation(), unusedInitialization(), passwordSetup))
                .setControllerAdvice(new TenantCreationExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-password-setups",
                        UUID.fromString("019535d9-0000-7000-8000-000000000010"))
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("PASSWORD_SETUP_DELIVERY_PENDING"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void rejectsPasswordSetupResendWhenCurrentPlatformRoleIsMissing() throws Exception {
        PlatformAdminAuthorizer denied = authorization -> {
            throw new PlatformAuthorizationDeniedException();
        };
        MockMvc mvc = mvc(denied, unusedCreation());

        mvc.perform(post("/api/v1/platform/tenants/{tenantId}/administrator-password-setups",
                        UUID.fromString("019535d9-0000-7000-8000-000000000010"))
                        .header("Authorization", "Bearer platform-token")
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_AUTHORIZATION_DENIED"));
    }

    private static MockMvc mvc(
            PlatformAdminAuthorizer authorizer, RecoverableTenantCreationService creation) {
        return MockMvcBuilders.standaloneSetup(new TenantCreationController(
                        authorizer, creation,
                        unusedInitialization(), unusedPasswordSetup()))
                .setControllerAdvice(new TenantCreationExceptionHandler())
                .build();
    }

    private static InitializeTenantAdministratorService unusedInitialization() {
        return new InitializeTenantAdministratorService(null, null, null, null, null, null);
    }

    private static ResendAdministratorPasswordSetupService unusedPasswordSetup() {
        return new ResendAdministratorPasswordSetupService(null, null, null, null, null, null);
    }

    private static RecoverableTenantCreationService unusedCreation() {
        return new RecoverableTenantCreationService(null, null, null) {
            @Override
            public TenantCreationResult create(
                    UUID callerIdentityId,
                    UUID idempotencyKey,
                    String displayName,
                    Instant expiresAt,
                    String traceId) {
                throw new AssertionError("不应进入 Tenant 创建用例");
            }
        };
    }
}
