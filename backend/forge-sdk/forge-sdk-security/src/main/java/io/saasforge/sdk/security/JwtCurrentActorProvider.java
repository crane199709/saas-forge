package io.saasforge.sdk.security;

import io.saasforge.sdk.core.CurrentActorProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 以当前线程已认证的 JWT {@code sub} 作为操作人。
 *
 * <p>与 {@link TenantContext}、{@link OperatorContext} 不同，没有认证时返回空而不是抛异常：标准列填充发生在每一次写入，
 * 包括 MQ 消费者等本就没有请求身份的线程。
 */
public final class JwtCurrentActorProvider implements CurrentActorProvider {
    /**
     * 读取当前线程的认证信息。
     *
     * @return 已认证 {@link JwtAuthenticationToken} 的 {@code sub}；不存在或未认证时为空
     */
    @Override
    public Optional<UUID> currentActor() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication
                && authentication.isAuthenticated()) {
            return Optional.of(UUID.fromString(authentication.getToken().getSubject()));
        }
        return Optional.empty();
    }
}
