package io.saas.forge.audit.infrastructure.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuditConsumerTopology {
    private final String iamInputTopic;
    private final String tenantInputTopic;
    private final String iamIsolationTopic;
    private final String tenantIsolationTopic;
    private final String exampleInputTopic;
    private final String exampleIsolationTopic;

    public AuditConsumerTopology(
            @Value("${saas.forge.audit.iam-session-topic}") String iamInputTopic,
            @Value("${saas.forge.audit.tenant-access-topic}") String tenantInputTopic,
            @Value("${saas.forge.audit.iam-session-isolation-topic}") String iamIsolationTopic,
            @Value("${saas.forge.audit.tenant-isolation-topic}") String tenantIsolationTopic,
            @Value("${saas.forge.audit.example-topic}") String exampleInputTopic,
            @Value("${saas.forge.audit.example-isolation-topic}") String exampleIsolationTopic) {
        this.iamInputTopic = iamInputTopic;
        this.tenantInputTopic = tenantInputTopic;
        this.iamIsolationTopic = iamIsolationTopic;
        this.tenantIsolationTopic = tenantIsolationTopic;
        this.exampleInputTopic = exampleInputTopic;
        this.exampleIsolationTopic = exampleIsolationTopic;
        if (java.util.stream.Stream.of(iamInputTopic, tenantInputTopic, exampleInputTopic,
                iamIsolationTopic, tenantIsolationTopic, exampleIsolationTopic).distinct().count() != 6) {
            throw new IllegalArgumentException("Audit Consumer 的输入与隔离 Topic 必须相互独立");
        }
    }

    public String consumerName(String inputTopic) {
        if (iamInputTopic.equals(inputTopic)) {
            return SessionStartedEventValidator.CONSUMER_NAME;
        }
        if (tenantInputTopic.equals(inputTopic)) {
            return TenantCreatedEventValidator.CONSUMER_NAME;
        }
        if (exampleInputTopic.equals(inputTopic)) return ExampleFactEventValidator.CONSUMER_NAME;
        throw new IllegalArgumentException("消息不属于已配置的 Audit Consumer Topic");
    }

    public String isolationTopic(String inputTopic) {
        if (iamInputTopic.equals(inputTopic)) {
            return iamIsolationTopic;
        }
        if (tenantInputTopic.equals(inputTopic)) {
            return tenantIsolationTopic;
        }
        if (exampleInputTopic.equals(inputTopic)) return exampleIsolationTopic;
        throw new IllegalArgumentException("消息不属于已配置的 Audit Consumer Topic");
    }
}
