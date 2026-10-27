package io.saasforge.iam.permission.filter;

import io.saasforge.iam.permission.config.ManifestProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 以服务专属的上报令牌认证清单上报请求，认证主体为令牌所属的服务名。令牌不匹配时不设置认证，由过滤链返回 401。
 *
 * <p>服务间调用不走 Tenant Realm 的 OAuth2：业务服务启动时没有用户身份，也不应持有可签发用户 Token 的凭证。
 */
public final class ServiceTokenFilter extends OncePerRequestFilter {
    private static final String BEARER = "Bearer ";

    private final Map<String, byte[]> tokens;

    public ServiceTokenFilter(ManifestProperties properties) {
        this.tokens = new HashMap<>();
        properties.tokens().forEach((service, token) -> tokens.put(service, token.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)) {
            byte[] presented = header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
            // 逐个以常量时间比较，响应时间不泄露令牌前缀。
            String service = null;
            for (var entry : tokens.entrySet()) {
                if (MessageDigest.isEqual(entry.getValue(), presented)) service = entry.getKey();
            }
            if (service != null) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(service, null, List.of()));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }
}
