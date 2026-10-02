package io.saas.forge.example;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.saas.forge.sdk.tenant.TenantContextSnapshot;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 在业务事务内保存已提交事实的不可变快照；不接受正文、名称、描述或凭据。 */
@Component
class ProjectFactOutbox {
    private final ProjectOutboxMapper mapper;
    private final ObjectMapper json;
    private final String topic;

    ProjectFactOutbox(ProjectOutboxMapper mapper, ObjectMapper json,
            @Value("${saas.forge.environment}") String environment) {
        if (!environment.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("Example environment 非法");
        this.mapper = mapper; this.json = json;
        this.topic = "saas.forge." + environment + ".project-service.events";
    }

    void append(TenantContextSnapshot actor, String resource, String action, UUID id, UUID project, long version) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("业务事实必须在事务中追加");
        if (!java.util.Set.of("project", "task").contains(resource)
                || !java.util.Set.of("created", "updated", "deleted").contains(action))
            throw new IllegalArgumentException("未登记的业务事实");
        String type = "com.saas.forge.example." + resource + "." + action + ".v1";
        String schema = "example-" + resource + "-" + action + ".v1.schema.json";
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        String eventId = mapper.nextId();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0"); event.put("id", eventId);
        event.put("source", "urn:saas.forge:project-service"); event.put("type", type);
        event.put("subject", id.toString()); event.put("time", now.toInstant().toString());
        event.put("datacontenttype", "application/json");
        event.put("dataschema", "https://saas.forge.io/contracts/events/" + schema);
        var span = Span.current().getSpanContext();
        if (span.isValid()) event.put("traceId", span.getTraceId());
        event.put("data", Map.of("tenantId", actor.tenantId().toString(), "actorIdentityId", actor.identityId().toString(),
                "membershipId", actor.membershipId().toString(), "resourceId", id.toString(),
                "projectId", project.toString(), "version", version));
        Map<String, String> carrier = new java.util.HashMap<>();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
        if (mapper.insert(new ProjectOutboxMapper.NewEvent(eventId, now, topic, project.toString(),
                json.writeValueAsString(event), carrier.get("traceparent"))) != 1)
            throw new IllegalStateException("Outbox 写入失败");
    }
}
