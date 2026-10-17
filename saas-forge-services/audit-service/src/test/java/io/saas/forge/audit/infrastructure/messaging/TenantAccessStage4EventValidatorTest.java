package io.saas.forge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saas.forge.audit.application.AuditRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TenantAccessStage4EventValidatorTest {
    private static final String TOPIC = TenantCreatedEventValidatorTest.TOPIC;
    private static final String TENANT = TenantCreatedEventValidatorTest.tenantId();
    private static final String ACTOR = TenantCreatedEventValidatorTest.actorIdentityId();
    private static final String MEMBERSHIP = "019535d9-0001-7000-8000-000000000041";
    private static final String IDENTITY = "019535d9-0001-7000-8000-000000000042";
    private static final String ASSET = "019535d9-0001-7000-8000-000000000043";
    private static final String FAVICON = "019535d9-0001-7000-8000-000000000044";
    private static final String EVENT = "019535d9-0001-7000-8000-000000000045";
    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private final ObjectMapper json = new ObjectMapper();
    private final TenantAccessEventValidator validator = new TenantAccessEventValidator(
            json, new TenantCreatedEventValidator(json, TOPIC), TOPIC);

    @Test
    void membershipDisableAndEnableFactsRetainTheTargetAndNeverStoreSessionSecrets() {
        for (String action : new String[] {"disabled", "enabled"}) {
            Map<String, Object> data = baseData();
            data.put("membershipId", MEMBERSHIP);
            data.put("identityId", IDENTITY);
            data.put(action.equals("disabled") ? "quotaReleasePending" : "fenceReleasePending", true);
            Map<String, Object> event = envelope("membership." + action, MEMBERSHIP, data);

            AuditRecord record = accept(event, TENANT);
            assertEquals("MEMBERSHIP_" + action.toUpperCase(), record.action());
            assertEquals(MEMBERSHIP, record.resourceId().toString());
            assertEquals(ACTOR, record.actorIdentityId().toString());
            assertFalse(record.metadata().contains("password"));
            assertFalse(record.metadata().contains("token"));

            reject(event, "019535d9-0001-7000-8000-000000000046");
            data.put("sessionToken", "must-not-enter-audit");
            reject(event, TENANT);
            data.remove("sessionToken");
            data.put(action.equals("disabled") ? "quotaReleasePending" : "fenceReleasePending", false);
            reject(event, TENANT);
        }
    }

    @Test
    void brandDisableAndRemovalFactsCarryOnlyPolicySafeMetadata() {
        for (String action : new String[] {"asset-disabled", "asset-removed"}) {
            Map<String, Object> data = baseData();
            data.put("assetId", ASSET);
            data.put("kind", "FAVICON");
            if (action.equals("asset-removed")) {
                data.remove("actorIdentityId");
                data.put("reason", "REPLACED");
            }
            Map<String, Object> event = envelope("brand." + action, TENANT, data);

            AuditRecord record = accept(event, TENANT);
            assertEquals(action.equals("asset-disabled") ? "BRAND_ASSET_DISABLED" : "BRAND_ASSET_REMOVED",
                    record.action());
            assertEquals(ASSET, record.resourceId().toString());
            assertFalse(record.metadata().contains("objectKey"));
            assertFalse(record.metadata().contains("email"));

            data.put("objectKey", "private-bucket-key");
            reject(event, TENANT);
            data.remove("objectKey");
            data.put("kind", "SCRIPT");
            reject(event, TENANT);
            data.put("kind", "FAVICON");
            if (action.equals("asset-removed")) {
                data.put("reason", "UNREGISTERED");
                reject(event, TENANT);
            }
        }
    }

    @Test
    void brandProfileSavedLinksBothAssetsAndRejectsUncommittedOrSecretData() {
        Map<String, Object> data = baseData();
        data.put("revision", 2);
        data.put("logoAssetId", ASSET);
        data.put("faviconAssetId", FAVICON);
        Map<String, Object> event = envelope("brand.profile-saved", TENANT, data);

        AuditRecord record = accept(event, TENANT);
        assertEquals("BRAND_PROFILE_SAVED", record.action());
        assertEquals(TENANT, record.resourceId().toString());
        assertEquals(ACTOR, record.actorIdentityId().toString());
        assertEquals(TRACE, record.traceId());
        assertFalse(record.metadata().contains("displayName"));

        data.put("displayName", "must-not-enter-audit");
        reject(event, TENANT);
        data.remove("displayName");
        data.put("revision", 0);
        reject(event, TENANT);
        data.put("revision", 2);
        event.put("traceId", "00000000000000000000000000000000");
        reject(event, TENANT);
        event.put("traceId", TRACE);
        event.put("subject", MEMBERSHIP);
        reject(event, TENANT);
    }

    @Test
    void everyStage4FactRejectsForgedSourceTopicConsumerTraceAndSubject() {
        Map<String, Object> member = baseData();
        member.put("membershipId", MEMBERSHIP);
        member.put("identityId", IDENTITY);
        member.put("quotaReleasePending", true);
        Map<String, Object> enabled = baseData();
        enabled.put("membershipId", MEMBERSHIP);
        enabled.put("identityId", IDENTITY);
        enabled.put("fenceReleasePending", true);
        Map<String, Object> disabledAsset = baseData();
        disabledAsset.put("assetId", ASSET);
        disabledAsset.put("kind", "LOGO");
        Map<String, Object> removedAsset = baseData();
        removedAsset.remove("actorIdentityId");
        removedAsset.put("assetId", ASSET);
        removedAsset.put("kind", "LOGO");
        removedAsset.put("reason", "UNBOUND");
        Map<String, Object> profile = baseData();
        profile.put("revision", 1);
        profile.put("logoAssetId", ASSET);
        profile.put("faviconAssetId", FAVICON);
        for (Map<String, Object> event : List.of(
                envelope("membership.disabled", MEMBERSHIP, member),
                envelope("membership.enabled", MEMBERSHIP, enabled),
                envelope("brand.asset-disabled", TENANT, disabledAsset),
                envelope("brand.asset-removed", TENANT, removedAsset),
                envelope("brand.profile-saved", TENANT, profile))) {
            assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                    "saas.forge.prod.tenant-access-service.events", TENANT,
                    TenantCreatedEventValidator.CONSUMER_NAME, json.writeValueAsString(event)));
            assertThrows(InvalidAuditEventException.class, () -> validator.validate(
                    TOPIC, TENANT, "audit-service.other", json.writeValueAsString(event)));
            event.put("source", "urn:saas.forge:other");
            reject(event, TENANT);
            event.put("source", TenantCreatedEventValidator.SOURCE);
            event.put("traceId", "00000000000000000000000000000000");
            reject(event, TENANT);
            event.put("traceId", TRACE);
            event.put("subject", IDENTITY);
            reject(event, TENANT);
        }
    }

    private Map<String, Object> baseData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", TENANT);
        data.put("actorIdentityId", ACTOR);
        return data;
    }

    private Map<String, Object> envelope(String kind, String subject, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("specversion", "1.0");
        event.put("id", EVENT);
        event.put("source", TenantCreatedEventValidator.SOURCE);
        event.put("type", "com.saas.forge." + kind + ".v1");
        event.put("subject", subject);
        event.put("time", "2026-10-14T10:15:30Z");
        event.put("datacontenttype", "application/json");
        event.put("dataschema", "https://saas.forge.io/contracts/events/" + kind.replace('.', '-')
                + ".v1.schema.json");
        event.put("traceId", TRACE);
        event.put("data", data);
        return event;
    }

    private AuditRecord accept(Map<String, Object> event, String orderingKey) {
        return validator.validate(TOPIC, orderingKey, TenantCreatedEventValidator.CONSUMER_NAME,
                json.writeValueAsString(event)).orElseThrow();
    }

    private void reject(Map<String, Object> event, String orderingKey) {
        assertThrows(InvalidAuditEventException.class, () -> accept(event, orderingKey));
    }
}
