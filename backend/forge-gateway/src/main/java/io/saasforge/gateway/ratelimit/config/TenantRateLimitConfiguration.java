package io.saasforge.gateway.ratelimit.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantRateLimitProperties.class)
class TenantRateLimitConfiguration {}
