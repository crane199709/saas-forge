package io.saasforge.iam.security.service;

import io.saasforge.iam.clientip.service.ClientIpResolver;
import io.saasforge.iam.security.config.ClientCredentials;
import io.saasforge.iam.security.config.IamProperties;
import io.saasforge.sdk.core.ClientIpHeader;
import java.util.Map;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** 以注册客户端身份调用本进程的标准 Token 端点，供两个 Realm 的 Token 交换适配器共用。 */
@Service
public class TokenEndpointClient {
    private final ServletWebServerApplicationContext application;
    private final ClientCredentials credentials;
    private final ServerProperties server;
    private final String tokenBaseUri;

    TokenEndpointClient(
            ServletWebServerApplicationContext application,
            ClientCredentials credentials,
            ServerProperties server,
            IamProperties properties) {
        this.application = application;
        this.credentials = credentials;
        this.server = server;
        this.tokenBaseUri = properties.tokenBaseUri();
    }

    /**
     * 提交一次 Token 请求并返回标准 Token 响应。
     *
     * @throws HttpClientErrorException 标准端点拒绝授权时原样抛出，由调用方按 OAuth2 错误码转换为业务错误
     */
    public Map<?, ?> grant(String clientId, MultiValueMap<String, String> form) {
        // 地址只来自服务端配置，不能由请求头或客户端指定；所有 grant 仍由标准过滤器校验。
        return RestClient.create(tokenBaseUri())
                .post()
                .uri("/oauth2/token")
                .headers(headers -> {
                    headers.setBasicAuth(clientId, credentials.secret());
                    // 本进程 HTTP 调用会替换连接 IP，必须保留外层适配器收到的可信来源。
                    String ip = ClientIpResolver.currentIp();
                    if (ip != null) {
                        headers.set(ClientIpHeader.NAME, ip);
                    }
                })
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    private String tokenBaseUri() {
        if (!tokenBaseUri.isBlank()) {
            return tokenBaseUri;
        }
        var address = server.getAddress();
        String host = address == null || address.isAnyLocalAddress() ? "127.0.0.1" : address.getHostAddress();
        // IPv6 字面量必须用方括号区分地址内的冒号与端口分隔符。
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        boolean tls = server.getSsl() != null && server.getSsl().isEnabled();
        return UriComponentsBuilder.newInstance()
                .scheme(tls ? "https" : "http")
                .host(host)
                .port(application.getWebServer().getPort())
                .path(server.getServlet().getContextPath())
                .build()
                .toUriString();
    }
}
