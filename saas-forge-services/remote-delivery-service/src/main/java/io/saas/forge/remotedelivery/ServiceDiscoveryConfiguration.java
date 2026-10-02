package io.saas.forge.remotedelivery;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.cloud.nacos.NacosServiceManager;
import io.saas.forge.discovery.DiscoveredGrpcChannel;
import io.saas.forge.discovery.NacosGrpcChannels;
import io.saas.forge.discovery.NacosServiceEndpoints;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.ChannelCredentialsProvider;
import org.springframework.grpc.client.ClientInterceptorsConfigurer;
import org.springframework.grpc.client.GrpcChannelBuilderCustomizer;

/** 启动参数仅决定本实例的注册信息，下游地址由 Nacos 按调用发现。 */
@Configuration(proxyBeanMethods = false)
public class ServiceDiscoveryConfiguration {
    @Bean
    NacosGrpcChannels nacosGrpcChannels(ChannelCredentialsProvider credentials,
            ClientInterceptorsConfigurer interceptors, ObjectProvider<GrpcChannelBuilderCustomizer<?>> customizers) {
        return new NacosGrpcChannels(credentials, interceptors, customizers.orderedStream().toList());
    }

    @Bean
    NacosServiceEndpoints serviceEndpoints(NacosServiceManager manager, NacosDiscoveryProperties properties) {
        return new NacosServiceEndpoints(manager.getNamingService(), properties.getGroup());
    }

    @Bean
    DiscoveredGrpcChannel iamServiceChannel(NacosServiceEndpoints endpoints, NacosGrpcChannels channels) {
        return channels.channel(endpoints, "iam-service", "iam");
    }

    @Bean
    DiscoveredGrpcChannel tenantAccessServiceChannel(NacosServiceEndpoints endpoints, NacosGrpcChannels channels) {
        return channels.channel(endpoints, "tenant-access-service", "tenant-access");
    }

    @Bean
    org.springframework.web.client.RestClient remoteDeliveryIamRestClient(NacosServiceEndpoints endpoints) {
        return endpoints.httpClient("iam-service");
    }
}
