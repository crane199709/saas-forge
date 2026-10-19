package io.saas.forge.gateway.config;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class RedisGatewayUserTokenRevocationCheckerTest {

    private static final UUID JTI = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abf");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abd");
    private static final UUID TENANT_ID = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abe");
    private static final UUID IDENTITY_ID = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abc");

    @Test
    void rejectsIdentityFenceEvenForPlatformTokenWithoutTenantContext() {
        GatewayIamRevocationRedisKeys keys = new GatewayIamRevocationRedisKeys("test");
        AtomicReference<List<String>> observed = new AtomicReference<>();
        GatewayRedisValueReader values = names -> {
            observed.set(names);
            return Arrays.asList("1", null, null, "1");
        };
        assertThrows(GatewayUserTokenInvalidException.class,
                () -> new RedisGatewayUserTokenRevocationChecker(values, "test")
                        .assertAllowed(JTI, "kid-1", null, null, IDENTITY_ID));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                keys.ready(), keys.jti(JTI), keys.kid("kid-1"), keys.identityFence(IDENTITY_ID)), observed.get());
    }

    @Test
    void allowsOnlyReadyIndexWithoutJtiKidOrFenceEntries() {
        AtomicReference<List<String>> observedKeys = new AtomicReference<>();
        GatewayRedisValueReader values = keys -> {
            observedKeys.set(keys);
            return Arrays.asList("1", null, null, null, null);
        };

        new RedisGatewayUserTokenRevocationChecker(values, "test")
                .assertAllowed(JTI, "kid-1", MEMBERSHIP_ID, TENANT_ID);

        GatewayIamRevocationRedisKeys keys = new GatewayIamRevocationRedisKeys("test");
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                keys.ready(),
                keys.jti(JTI),
                keys.kid("kid-1"),
                keys.tenantFence(TENANT_ID),
                keys.membershipFence(MEMBERSHIP_ID)), observedKeys.get());
    }

    @Test
    void treatsNotReadyAndRedisFailuresAsUnavailable() {
        GatewayRedisValueReader notReady = keys -> Arrays.asList("0", null, null);
        GatewayRedisValueReader unavailable = keys -> {
            throw new DataAccessResourceFailureException("Redis unavailable");
        };

        assertThrows(GatewayTokenRevocationStatusUnavailableException.class,
                () -> new RedisGatewayUserTokenRevocationChecker(notReady, "test")
                        .assertAllowed(JTI, "kid-1", null, null));
        assertThrows(GatewayTokenRevocationStatusUnavailableException.class,
                () -> new RedisGatewayUserTokenRevocationChecker(unavailable, "test")
                        .assertAllowed(JTI, "kid-1", null, null));
    }

    @Test
    void rejectsJtiKidTenantAndMembershipFenceEntries() {
        for (int index = 1; index < 5; index++) {
            String[] values = {"1", null, null, null, null};
            values[index] = "1";
            RedisGatewayUserTokenRevocationChecker checker = new RedisGatewayUserTokenRevocationChecker(
                    keys -> Arrays.asList(values), "test");

            assertThrows(GatewayUserTokenInvalidException.class,
                    () -> checker.assertAllowed(JTI, "kid-1", MEMBERSHIP_ID, TENANT_ID));
        }
    }

}
