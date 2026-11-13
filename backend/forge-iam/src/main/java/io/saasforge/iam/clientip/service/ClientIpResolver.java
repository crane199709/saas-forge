package io.saasforge.iam.clientip.service;

import io.saasforge.sdk.core.ClientIpHeader;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** IAM 请求的可信客户端 IP，供登录锁定、Token 透传和租户准入共用。 */
public final class ClientIpResolver {
    private ClientIpResolver() {}

    /**
     * 取网关覆盖的来源头，服务直连时回退连接地址；无请求上下文时返回 null。
     * 不读取 X-Forwarded-For，避免采信客户端伪造的代理链。
     */
    public static String currentIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        var request = attributes.getRequest();
        String forwarded = request.getHeader(ClientIpHeader.NAME);
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.strip();
    }
}
