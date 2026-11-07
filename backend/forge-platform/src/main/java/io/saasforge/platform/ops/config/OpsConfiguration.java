package io.saasforge.platform.ops.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration(proxyBeanMethods = false)
class OpsConfiguration {
    @Bean
    @LoadBalanced
    RestClient.Builder opsRestClientBuilder() {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        // Blocking LoadBalancer 保留请求 scheme；lb 是服务定位协议，交给 HTTP 传输前须转换。
        return RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
            var transportRequest = new HttpRequestWrapper(request) {
                @Override
                public URI getURI() {
                    return UriComponentsBuilder.fromUri(super.getURI())
                            .scheme("http")
                            .build(true)
                            .toUri();
                }
            };
            return execution.execute(transportRequest, body);
        });
    }
}
