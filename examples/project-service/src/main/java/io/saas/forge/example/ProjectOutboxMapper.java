package io.saas.forge.example;

import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface ProjectOutboxMapper {
    String nextId();
    int insert(NewEvent event);
    ClaimedEvent claim(Claim claim);
    int published(Completion completion);
    int retry(Completion completion);

    record NewEvent(String eventId, OffsetDateTime occurredAt, String topic, String orderingKey,
                    String payload, String traceparent) {}
    record Claim(String token, OffsetDateTime at, OffsetDateTime until) {}
    record ClaimedEvent(String eventId, String topic, String orderingKey, String payload,
                        String traceparent, String claimToken, int attemptCount) {}
    record Completion(String eventId, String token, OffsetDateTime at, String failure) {}
}
