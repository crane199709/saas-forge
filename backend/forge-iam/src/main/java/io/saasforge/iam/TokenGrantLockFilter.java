package io.saasforge.iam;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class TokenGrantLockFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis;
    private final RedisAuthorizationService authorizations;

    TokenGrantLockFilter(StringRedisTemplate redis, RedisAuthorizationService authorizations) {
        this.redis = redis;
        this.authorizations = authorizations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String grant = request.getParameter("grant_type");
        String value = "authorization_code".equals(grant)
                ? request.getParameter("code")
                : "refresh_token".equals(grant) ? request.getParameter("refresh_token") : null;
        if (!request.getServletPath().equals("/oauth2/token") || value == null) {
            chain.doFilter(request, response);
            return;
        }
        // 客户端认证过滤器会放行未提供凭证的请求，必须显式确认认证结果再产生撤销副作用。
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2ClientAuthenticationToken client)
                || !client.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        // 已确认的重放不能因锁争用而漏掉撤销。
        if ("refresh_token".equals(grant) && authorizations.revokeUsedRefreshToken(value)) {
            invalidGrant(response);
            return;
        }
        String authorizationId = redis.opsForValue().get(RedisAuthorizationService.tokenKey(value));
        // 同一授权的正常消费互斥；重放撤销通过授权记录的原子更新条件阻止复活。
        String key = authorizationId == null
                ? RedisAuthorizationService.tokenKey(value) + ":lock"
                : "iam:global:oauth-grant-lock:" + authorizationId;
        String owner = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, Duration.ofSeconds(30)))) {
            // 等待锁的短暂窗口内也可能发生轮换，重新确认旧凭证是否已经被消费。
            if ("refresh_token".equals(grant)) authorizations.revokeUsedRefreshToken(value);
            invalidGrant(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            redis.execute(
                    new DefaultRedisScript<Long>(
                            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                            Long.class),
                    List.of(key),
                    owner);
        }
    }

    private void invalidGrant(HttpServletResponse response) throws IOException {
        response.setStatus(400);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"invalid_grant\"}");
    }
}
