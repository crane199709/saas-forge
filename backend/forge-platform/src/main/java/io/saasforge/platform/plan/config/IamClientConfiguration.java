package io.saasforge.platform.plan.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 调用 iam 内部接口的客户端：读取权限码目录与 Plan 变更影响共用同一地址、令牌与超时。 */
@Configuration(proxyBeanMethods = false)
class IamClientConfiguration {
    @Bean
    RestClient iamRestClient(IamCatalogProperties properties) {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl(properties.uri())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
                .build();
    }
}
