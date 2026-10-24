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
import org.springframework.web.filter.OncePerRequestFilter;

final class TokenGrantLockFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis;

    TokenGrantLockFilter(StringRedisTemplate redis) {
        this.redis = redis;
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
        String key = RedisAuthorizationService.tokenKey(value) + ":lock";
        String owner = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, Duration.ofSeconds(30)))) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid_grant\"}");
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
}
