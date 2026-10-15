package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class SessionStartedEventValidatorTest {
    private static final String TOPIC = "saas.forge.test.iam-service.events";
    private final SessionStartedEventValidator validator =
            new SessionStartedEventValidator(new ObjectMapper(), TOPIC);

    @Test
    void mapsOnlyNormalizedAndWhitelistedFields() {
        var record = validator.validate(
                TOPIC,
                "019535d9-0001-7000-8000-000000000002",
                SessionStartedEventValidator.CONSUMER_NAME,
                event("")).metadata();

        assertEquals(
                "{\"purpose\":\"USER_TENANT\",\"contextType\":\"TENANT\","
                        + "\"sessionOutcome\":\"ACCESS_TOKEN_ISSUED\"}",
                record);
        assertNull(validator.validate(
                TOPIC,
                "019535d9-0001-7000-8000-000000000002",
                SessionStartedEventValidator.CONSUMER_NAME,
                event("")).traceId());
    }

    @Test
    void firstPasswordChangeAndTenantSelectionAreAuditedWithoutTokenMaterial() {
        String base = event("");
        String traced = base.replace("\"data\":",
                "\"traceId\":\"1234567890abcdef1234567890abcdef\",\"data\":");
        for (String payload : new String[] {
                traced.replace("USER_TENANT", "INITIAL_PASSWORD_CHANGE")
                        .replace("ACCESS_TOKEN_ISSUED", "PASSWORD_CHANGE_REQUIRED")
                        .replace("\"TENANT\"", "\"PLATFORM\""),
                traced.replace("USER_TENANT", "USER_TENANT_SELECTION")
                        .replace("ACCESS_TOKEN_ISSUED", "CONTEXT_SELECTION_REQUIRED")
                        .replace("\"TENANT\"", "\"PLATFORM\"")
        }) {
            var record = validator.validate(TOPIC, identityId(), consumer(), payload);
            assertEquals("SESSION_STARTED", record.action());
            assertEquals("1234567890abcdef1234567890abcdef", record.traceId());
            assertFalse(record.metadata().contains("token"));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidContracts")
    void rejectsContractAndSecurityBoundaryViolations(String topic, String key, String consumer, String payload) {
        assertThrows(InvalidAuditEventException.class, () -> validator.validate(topic, key, consumer, payload));
    }

    static Stream<Arguments> invalidContracts() {
        String valid = event("");
        return Stream.of(
                Arguments.of("saas.forge.prod.iam-service.events", identityId(), consumer(), valid),
                Arguments.of(TOPIC, familyId(), consumer(), valid),
                Arguments.of(TOPIC, identityId(), "audit-service.other", valid),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "urn:saas.forge:iam-service", "urn:saas.forge:other")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "iam-session-started.v1.schema.json", "other.v1.schema.json")),
                Arguments.of(TOPIC, identityId(), consumer(), event(",\"password\":\"secret\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "\"occurredAt\":\"2026-08-28T10:15:30Z\"",
                        "\"occurredAt\":\"2026-08-28T10:15:31Z\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace(
                        "019535d9-0001-7000-8000-000000000001", "019535d9-0001-4000-8000-000000000001")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace("\"purpose\":\"USER_TENANT\"",
                        "\"purpose\":\"SERVICE_CLIENT\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace("\"contextType\":\"TENANT\"",
                        "\"contextType\":\"CROSS_TENANT\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace("\"result\":\"ACCESS_TOKEN_ISSUED\"",
                        "\"result\":\"TOKEN_DISCLOSED\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace("\"data\":",
                        "\"traceId\":\"00000000000000000000000000000000\",\"data\":")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace("\"time\":\"2026-08-28T10:15:30Z\"",
                        "\"time\":\"2026-08-28T11:15:30+01:00\"")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace("\"data\":",
                        "\"cookie\":\"secret\",\"data\":")),
                Arguments.of(TOPIC, identityId(), consumer(), valid.replace("\"subject\":\"" + familyId() + "\"",
                        "\"subject\":\"" + identityId() + "\"")));
    }

    static String event(String extraDataField) {
        return """
                {"specversion":"1.0","id":"019535d9-0001-7000-8000-000000000001",
                "source":"urn:saas.forge:iam-service","type":"com.saas.forge.iam.session.started.v1",
                "subject":"019535d9-0001-7000-8000-000000000003","time":"2026-08-28T10:15:30Z",
                "datacontenttype":"application/json",
                "dataschema":"https://saas.forge.io/contracts/events/iam-session-started.v1.schema.json",
                "data":{"familyId":"019535d9-0001-7000-8000-000000000003",
                "identityId":"019535d9-0001-7000-8000-000000000002","purpose":"USER_TENANT",
                "contextType":"TENANT","result":"ACCESS_TOKEN_ISSUED",
                "occurredAt":"2026-08-28T10:15:30Z"%s}}
                """.formatted(extraDataField).replace("\n", "");
    }

    private static String identityId() {
        return "019535d9-0001-7000-8000-000000000002";
    }

    private static String familyId() {
        return "019535d9-0001-7000-8000-000000000003";
    }

    private static String consumer() {
        return SessionStartedEventValidator.CONSUMER_NAME;
    }
}
