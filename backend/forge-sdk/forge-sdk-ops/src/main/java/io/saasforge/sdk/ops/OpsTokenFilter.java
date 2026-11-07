package io.saasforge.sdk.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class OpsTokenFilter extends OncePerRequestFilter {
    private final byte[] token;

    OpsTokenFilter(OpsProperties properties) {
        token = properties.token().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 运维链只认可本服务的令牌，不能沿用会话或其他安全链留下的用户身份。
        var context = SecurityContextHolder.createEmptyContext();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null
                && header.startsWith("Bearer ")
                && MessageDigest.isEqual(token, header.substring(7).getBytes(StandardCharsets.UTF_8))) {
            context.setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("platform-ops", null, List.of()));
        }
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
