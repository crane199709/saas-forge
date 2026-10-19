package io.saas.forge.iam.application.authentication;

import java.util.UUID;

@FunctionalInterface
public interface UserTokenIssuanceFence {
    void assertIssuable(UUID membershipId, UUID tenantId);

    /** Identity 密码恢复期间，任何工作上下文与等待会话均不得创建或轮换。 */
    default void assertIdentityIssuable(UUID identityId) { }
}
