package io.saas.forge.audit.config;

import io.saas.forge.audit.infrastructure.messaging.IamSessionKafkaConsumer;
import io.saas.forge.audit.infrastructure.messaging.ExampleFactKafkaConsumer;
import io.saas.forge.audit.infrastructure.messaging.TenantAccessKafkaConsumer;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdminOperations;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Audit 只有在正式迁移可见、Kafka 可连接且所有已启用 Consumer 都获得目标分区后才 Ready。
 * 每次探测都重新读取外部状态，依赖恢复后无需重启即可重新就绪。
 */
@Component("auditRuntimeReadiness")
public class AuditRuntimeReadinessHealthIndicator implements HealthIndicator {
    private static final String MIGRATION_QUERY = """
            SELECT count(*)
            FROM flyway_schema_history
            WHERE version = ? AND success
            """;

    private final JdbcOperations jdbc;
    private final KafkaAdminOperations kafkaAdmin;
    private final KafkaListenerEndpointRegistry listeners;
    private final String requiredMigrationVersion;
    private final List<RequiredAssignment> requiredAssignments;

    public AuditRuntimeReadinessHealthIndicator(
            JdbcOperations jdbc,
            KafkaAdminOperations kafkaAdmin,
            KafkaListenerEndpointRegistry listeners,
            @Value("${saas.forge.audit.required-migration-version}") String requiredMigrationVersion,
            @Value("${saas.forge.audit.iam-session-topic}") String iamSessionTopic,
            @Value("${saas.forge.audit.tenant-access-topic}") String tenantAccessTopic,
            @Value("${saas.forge.audit.example-topic}") String exampleTopic,
            @Value("${saas.forge.audit.example-consumer.enabled:false}") boolean exampleEnabled) {
        this.jdbc = jdbc;
        this.kafkaAdmin = kafkaAdmin;
        this.listeners = listeners;
        this.requiredMigrationVersion = requiredMigrationVersion;
        var assignments = new java.util.ArrayList<>(List.of(
                new RequiredAssignment(IamSessionKafkaConsumer.LISTENER_ID, iamSessionTopic),
                new RequiredAssignment(TenantAccessKafkaConsumer.LISTENER_ID, tenantAccessTopic)));
        if (exampleEnabled) assignments.add(new RequiredAssignment(ExampleFactKafkaConsumer.LISTENER_ID, exampleTopic));
        this.requiredAssignments = List.copyOf(assignments);
    }

    @Override
    public Health health() {
        Health database = databaseHealth();
        if (!database.getStatus().equals(org.springframework.boot.health.contributor.Status.UP)) {
            return database;
        }
        try {
            String[] topics = requiredAssignments.stream()
                    .map(RequiredAssignment::topic)
                    .toArray(String[]::new);
            if (!kafkaAdmin.describeTopics(topics).keySet().containsAll(
                    requiredAssignments.stream().map(RequiredAssignment::topic).toList())) {
                return down("kafka", "unavailable");
            }
        } catch (RuntimeException exception) {
            return down("kafka", "unavailable");
        }
        for (RequiredAssignment required : requiredAssignments) {
            MessageListenerContainer listener = listeners.getListenerContainer(required.listenerId());
            if (listener == null || !listener.isRunning() || listener.getAssignedPartitions() == null
                    || listener.getAssignedPartitions().stream()
                            .noneMatch(partition -> required.topic().equals(partition.topic()))) {
                return down("consumerAssignment", required.listenerId());
            }
        }
        return Health.up()
                .withDetail("databaseMigration", requiredMigrationVersion)
                .withDetail("kafka", "connected")
                .withDetail("consumerAssignments", requiredAssignments.size())
                .build();
    }

    private Health databaseHealth() {
        try {
            Integer applied = jdbc.queryForObject(
                    MIGRATION_QUERY, Integer.class, requiredMigrationVersion);
            if (applied == null || applied != 1) {
                return down("databaseMigration", "pending");
            }
            return Health.up().build();
        } catch (RuntimeException exception) {
            return down("database", "unavailable");
        }
    }

    private static Health down(String dependency, String state) {
        return Health.down().withDetail(dependency, state).build();
    }

    private record RequiredAssignment(String listenerId, String topic) {
    }
}
