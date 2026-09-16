package io.saas.forge.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.saas.forge.contracts.route.HttpRouteCatalog;
import io.saas.forge.contracts.route.HttpRouteCatalogLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

class RepositoryStandardsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = Path.of(System.getProperty("repositoryRoot"));
    private static final Set<String> SERVICE_ARTIFACTS = registeredServiceArtifacts();
    private static final Map<String, String> SERVICE_PACKAGES = Map.of(
            "iam-service", "io.saas.forge.iam",
            "tenant-access-service", "io.saas.forge.tenantaccess",
            "entitlement-service", "io.saas.forge.entitlement",
            "audit-service", "io.saas.forge.audit");
    private static final Map<String, String> OPENAPI_TAG_OWNERS = Map.of(
            "Authentication", "iam-service",
            "Discovery", "iam-service",
            "OAuth clients", "iam-service",
            "Platform tenants", "tenant-access-service",
            "Platform entitlement bootstrap", "entitlement-service");
    private static final Map<String, String> OPENAPI_TAG_GENERATOR_NAMES = Map.of(
            "Authentication", "Authentication",
            "Discovery", "Discovery",
            "OAuth clients", "OAuthClients",
            "Platform tenants", "PlatformTenants",
            "Platform entitlement bootstrap", "PlatformEntitlementBootstrap");
    private static final Pattern ANNOTATED_SQL = Pattern.compile(
            "@(Select|Insert|Update|Delete)(Provider)?\\b");
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile(
            "V([1-9][0-9]*(?:\\.[0-9]+)*)__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql");
    private static final Pattern REPEATABLE_MIGRATION = Pattern.compile(
            "R__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql");
    private static final Pattern REPEATABLE_FORBIDDEN_SQL = Pattern.compile(
            "(?is)\\b(create|alter|drop)\\s+table\\b|\\b(insert|update|delete)\\s+(?:into|from)?\\s*");
    private static final Pattern CROSS_DATABASE_ACCESS = Pattern.compile(
            "(?is)\\b(dblink|postgres_fdw|foreign\\s+data\\s+wrapper|create\\s+server|import\\s+foreign\\s+schema)\\b");
    private static final Pattern LOG_EVENT = Pattern.compile(
            "^[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern EVENT_TYPE = Pattern.compile(
            "^com\\.saas\\.forge\\.[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)*\\.v[1-9][0-9]*$");
    private static final Pattern EVENT_SOURCE = Pattern.compile(
            "^urn:saas\\.forge:[a-z][a-z0-9-]*-service$");
    private static final Pattern EVENT_TOPIC = Pattern.compile(
            "^saas\\.forge\\.<environment>\\.[a-z][a-z0-9-]*-service\\.events$");
    private static final Pattern DECLARED_EVENT_TYPE = Pattern.compile(
            "String\\s+EVENT_TYPE\\s*=\\s*\"(com\\.saas\\.forge[^\"]+)\"");
    private static final Pattern CONSUMER_NAME = Pattern.compile(
            "^[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern MAPPER_NAMESPACE = Pattern.compile(
            "<mapper\\s+[^>]*namespace=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAPPER_STATEMENT = Pattern.compile(
            "<(?:select|insert|update|delete)\\b[^>]*\\bid=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAPPER_SELECT = Pattern.compile(
            "(?is)<select\\b([^>]*)>(.*?)</select>");
    private static final Pattern DML_RETURNING = Pattern.compile(
            "(?is)\\b(?:insert|update|delete)\\b.*\\breturning\\b");
    private static final Pattern AFFECT_DATA_TRUE = Pattern.compile(
            "\\baffectData\\s*=\\s*\"true\"");
    private static final Pattern FLUSH_CACHE_TRUE = Pattern.compile(
            "\\bflushCache\\s*=\\s*\"true\"");
    private static final Pattern MAPPER_METHOD = Pattern.compile(
            "(?m)^\\s*(?:[A-Za-z0-9_$.<>?, \\[\\]]+\\s+)([A-Za-z][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*;");
    private static final Pattern OPENAPI_PATH = Pattern.compile("^  (/[^:]+):$");
    private static final Pattern OPENAPI_METHOD = Pattern.compile("^    (get|post|put|patch|delete|head|options|trace):$");
    private static final Pattern OPENAPI_TAGS = Pattern.compile("^      tags: \\[([^]]+)]$");
    private static final Pattern OPENAPI_SERVICE_OWNER = Pattern.compile("^      x-saas\\.forge-service: ([a-z-]+)$");
    private static final Pattern OPENAPI_OPERATION_ID = Pattern.compile("^      operationId: ([A-Za-z][A-Za-z0-9]*)$");
    private static final Pattern OPENAPI_SECURITY = Pattern.compile("^      security:(.*)$");
    private static final Pattern SPRING_HTTP_MAPPING = Pattern.compile(
            "@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\\b");
    private static final Pattern REST_CONTROLLER = Pattern.compile("@RestController\\b");
    private static final Pattern GENERATED_API_IMPLEMENTATION = Pattern.compile(
            "class\\s+[A-Za-z][A-Za-z0-9_]*\\s+implements\\s+[A-Za-z0-9_., <>?]+Api\\b");
    private static final Pattern FINAL_REPOSITORY_CLASS = Pattern.compile(
            "@Repository(?:\\([^)]*\\))?\\s+(?:public\\s+)?final\\s+class\\b");

    @Test
    void redisRegistryIsCompleteAndConsistent() throws Exception {
        Path schemaPath = REPOSITORY.resolve("saas-forge-contracts/redis/registry.schema.json");
        JsonNode schema = readJson(schemaPath);
        Set<String> requiredEntryFields = textSet(schema.at("/$defs/entry/required"));
        assertFalse(requiredEntryFields.isEmpty(), "Redis Registry Schema 必须声明必填字段");
        Pattern idPattern = Pattern.compile(schema.at("/$defs/entry/properties/id/pattern").asText());
        Pattern keyPatternRule = Pattern.compile(schema.at("/$defs/entry/properties/keyPattern/pattern").asText());
        Set<String> valueFormats = textSet(schema.at("/$defs/entry/properties/valueFormat/enum"));
        Set<String> failurePolicies = textSet(schema.at("/$defs/entry/properties/failurePolicy/enum"));
        Set<String> ttlModes = textSet(schema.at("/$defs/entry/properties/ttl/properties/mode/enum"));

        Set<String> ids = new HashSet<>();
        Set<String> keyPatterns = new HashSet<>();
        List<Path> registryFiles = filesUnder(REPOSITORY.resolve("saas-forge-contracts/redis/registry"), ".json");
        assertFalse(registryFiles.isEmpty(), "至少需要一个 Redis Registry");

        for (Path registryFile : registryFiles) {
            JsonNode registry = readJson(registryFile);
            assertEquals(1, registry.path("registryVersion").asInt(), registryFile + " registryVersion 必须为 1");
            String owner = requiredText(registry, "owner", registryFile);
            assertTrue(registry.path("entries").isArray(), registryFile + " entries 必须是数组");

            for (JsonNode entry : registry.path("entries")) {
                for (String field : requiredEntryFields) {
                    assertTrue(entry.has(field), registryFile + " 的 Key 登记缺少字段 " + field);
                }
                String id = requiredText(entry, "id", registryFile);
                String writer = requiredText(entry, "writer", registryFile);
                String keyPattern = requiredText(entry, "keyPattern", registryFile);
                assertTrue(entry.path("schemaVersion").isIntegralNumber(), id + " 的 schemaVersion 必须是整数");
                int schemaVersion = entry.path("schemaVersion").asInt();

                assertEquals(owner, writer, id + " 的 writer 必须等于 Registry owner");
                assertTrue(idPattern.matcher(id).matches(), "Redis Registry ID 不合法: " + id);
                assertTrue(ids.add(id), "Redis Registry ID 重复: " + id);
                assertTrue(keyPatternRule.matcher(keyPattern).matches(), id + " 的 Key 模式不合法");
                assertTrue(keyPatterns.add(keyPattern), "Redis Key 模式重复: " + keyPattern);
                assertTrue(id.endsWith(".v" + schemaVersion), id + " 的 ID 版本与 schemaVersion 不一致");
                assertTrue(keyPattern.contains(":v" + schemaVersion + ":"),
                        id + " 的 Key 版本与 schemaVersion 不一致");
                assertTrue(keyPattern.startsWith("sf:<environment>:" + owner + ":"),
                        id + " 的 Key 命名空间必须与 writer 一致");
                assertTrue(entry.path("readers").isArray() && !entry.path("readers").isEmpty(),
                        id + " 必须登记读取者");
                textSet(entry.path("readers"));
                assertTrue(valueFormats.contains(entry.path("valueFormat").asText()), id + " 的 valueFormat 不合法");
                assertTrue(failurePolicies.contains(entry.path("failurePolicy").asText()),
                        id + " 的 failurePolicy 不合法");
                assertTrue(ttlModes.contains(entry.at("/ttl/mode").asText()), id + " 的 TTL mode 不合法");
                assertTrue(entry.path("containsRawSensitiveData").isBoolean(),
                        id + " 的 containsRawSensitiveData 必须是布尔值");
                assertFalse(entry.path("containsRawSensitiveData").asBoolean(),
                        id + " 不得包含原始敏感数据");
                assertFalse(requiredText(entry, "maxCardinality", registryFile).isBlank(),
                        id + " 必须说明最大基数");
                assertFalse(requiredText(entry.path("ttl"), "expression", registryFile).isBlank(),
                        id + " 必须说明 TTL");
                String lower = (id + " " + entry.path("purpose").asText()).toLowerCase();
                assertFalse(lower.contains("permission") || lower.contains("feature") || lower.contains("quota"),
                        id + " 不得把 Permission、Feature 或 Quota 作为平台 Redis 用途");
            }
        }
    }

    @Test
    void eventEngineeringRegistryIsCompleteAndConsistent() throws Exception {
        Path schemaPath = REPOSITORY.resolve("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.schema.json");
        JsonNode schema = readJson(schemaPath);
        Set<String> requiredEntryFields = textSet(schema.at("/$defs/entry/required"));
        assertEquals(Set.of("type", "producerService", "source", "schema", "topic", "orderingKey", "allowedConsumers"),
                requiredEntryFields, "事件工程注册表字段发生变化时必须显式评审");

        JsonNode registry = readJson(REPOSITORY.resolve("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
        assertEquals(1, registry.path("registryVersion").asInt(), "事件工程注册表 registryVersion 必须为 1");
        assertTrue(registry.path("entries").isArray(), "事件工程注册表 entries 必须是数组");

        Set<String> registeredEvents = new HashSet<>();
        for (JsonNode entry : registry.path("entries")) {
            for (String field : requiredEntryFields) {
                assertTrue(entry.has(field), "事件工程登记缺少字段 " + field);
            }
            String type = requiredText(entry, "type", Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
            String producerService = requiredText(entry, "producerService",
                    Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
            String source = requiredText(entry, "source", Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
            String eventSchema = requiredText(entry, "schema", Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
            String topic = requiredText(entry, "topic", Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
            assertTrue(SERVICE_ARTIFACTS.contains(producerService), type + " 使用了未知生产服务 " + producerService);
            assertTrue(EVENT_TYPE.matcher(type).matches(), "事件 type 不合法: " + type);
            assertTrue(EVENT_SOURCE.matcher(source).matches(), "事件 source 不合法: " + source);
            assertEquals("urn:saas.forge:" + producerService, source, type + " 的 source 必须归属生产服务");
            assertTrue(EVENT_TOPIC.matcher(topic).matches(), "事件 topic 不合法: " + topic);
            assertEquals("saas.forge.<environment>." + producerService + ".events", topic,
                    type + " 的 topic 必须归属生产服务");
            assertFalse(requiredText(entry, "orderingKey", Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json")).isBlank(),
                    type + " 必须声明 orderingKey");
            assertTrue(registeredEvents.add(source + "|" + type), "事件 source/type 重复登记: " + source + " " + type);

            Path schemaFile = REPOSITORY.resolve(eventSchema).normalize();
            assertTrue(schemaFile.startsWith(REPOSITORY.resolve("saas-forge-contracts/saas-forge-event-contracts")),
                    type + " 的 schema 必须位于 saas-forge-contracts/saas-forge-event-contracts");
            assertTrue(Files.isRegularFile(schemaFile), type + " 的 schema 不存在: " + eventSchema);
            JsonNode eventSchemaJson = readJson(schemaFile);
            boolean referencesEnvelope = false;
            boolean declaresRegisteredType = false;
            for (JsonNode branch : eventSchemaJson.path("allOf")) {
                referencesEnvelope |= "cloudevents-envelope.v1.schema.json".equals(branch.path("$ref").asText());
                declaresRegisteredType |= type.equals(branch.at("/properties/type/const").asText());
            }
            assertTrue(referencesEnvelope, type + " 的 schema 必须组合 CloudEvents v1 信封");
            assertTrue(declaresRegisteredType, type + " 的 schema 必须约束登记的 type");

            assertTrue(entry.path("allowedConsumers").isArray(), type + " 的 allowedConsumers 必须是数组");
            Set<String> consumers = new HashSet<>();
            for (JsonNode consumer : entry.path("allowedConsumers")) {
                String service = requiredText(consumer, "service", Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
                String consumerName = requiredText(consumer, "consumerName",
                        Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
                assertTrue(SERVICE_ARTIFACTS.contains(service), type + " 使用了未知消费者服务 " + service);
                assertTrue(CONSUMER_NAME.matcher(consumerName).matches(),
                        type + " 的 consumerName 不合法: " + consumerName);
                assertTrue(consumers.add(service + "|" + consumerName),
                        type + " 的消费者重复登记: " + service + " " + consumerName);
            }
        }
    }

    @Test
    void tenantAuditConsumersSeparateCreatedFromLifecycleEvents() throws Exception {
        JsonNode entries = readJson(REPOSITORY.resolve("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"))
                .path("entries");
        Map<String, Set<String>> consumersByType = new HashMap<>();
        for (JsonNode entry : entries) {
            Set<String> consumers = new HashSet<>();
            for (JsonNode consumer : entry.path("allowedConsumers")) {
                consumers.add(consumer.path("consumerName").asText());
            }
            consumersByType.put(entry.path("type").asText(), consumers);
        }

        assertEquals(Set.of("audit-service.tenant-events"),
                consumersByType.get("com.saas.forge.tenant.created.v1"));
        assertEquals(Set.of("audit-service.tenant-lifecycle-events"),
                consumersByType.get("com.saas.forge.tenant.suspended.v1"));
    }

    @Test
    void serviceAndOAuthScopeRegistriesControlPublicRouteEligibility() throws Exception {
        Path serviceRegistryPath = REPOSITORY.resolve("saas-forge-contracts/services/engineering-registry.json");
        JsonNode serviceSchema = readJson(
                REPOSITORY.resolve("saas-forge-contracts/services/engineering-registry.schema.json"));
        assertEquals(Set.of("serviceId", "owner", "modulePath", "artifactId", "nacosServiceName",
                        "deployable", "gatewayRouteTargetAllowed"),
                textSet(serviceSchema.at("/$defs/entry/required")),
                "Service Registry 字段发生变化时必须显式评审");
        JsonNode serviceRegistry = readJson(serviceRegistryPath);
        assertEquals(1, serviceRegistry.path("registryVersion").asInt(), "Service Registry 版本必须为 1");
        Set<String> serviceIds = new HashSet<>();
        Set<String> routeTargets = new HashSet<>();
        for (JsonNode entry : serviceRegistry.path("entries")) {
            String serviceId = requiredText(entry, "serviceId", serviceRegistryPath);
            assertTrue(serviceIds.add(serviceId), "Service Registry serviceId 重复: " + serviceId);
            assertTrue(entry.path("deployable").isBoolean(), serviceId + " deployable 必须是布尔值");
            assertTrue(entry.path("gatewayRouteTargetAllowed").isBoolean(),
                    serviceId + " gatewayRouteTargetAllowed 必须是布尔值");
            if (entry.path("gatewayRouteTargetAllowed").asBoolean()) {
                routeTargets.add(serviceId);
            }
        }
        assertEquals(routeTargets, HttpRouteCatalogLoader.load().routes().stream()
                .filter(route -> !isAcceptanceRoute(route))
                .map(HttpRouteCatalog.Route::serviceId).collect(java.util.stream.Collectors.toSet()),
                "只有具有公网资格且拥有正式 OpenAPI operation 的服务才能进入 Route Catalog");

        Path scopeRegistryPath = REPOSITORY.resolve("saas-forge-contracts/security/oauth-scope-registry.json");
        JsonNode scopeSchema = readJson(
                REPOSITORY.resolve("saas-forge-contracts/security/oauth-scope-registry.schema.json"));
        assertEquals(Set.of("scope", "ownerServiceId", "clientTypes", "usage",
                        "gatewayRouteAllowed", "description"),
                textSet(scopeSchema.at("/$defs/entry/required")),
                "OAuth Scope Registry 字段发生变化时必须显式评审");
        JsonNode scopeRegistry = readJson(scopeRegistryPath);
        assertEquals(1, scopeRegistry.path("registryVersion").asInt(), "OAuth Scope Registry 版本必须为 1");
        Set<String> scopes = new HashSet<>();
        Set<String> publicScopes = new HashSet<>();
        for (JsonNode entry : scopeRegistry.path("entries")) {
            String scope = requiredText(entry, "scope", scopeRegistryPath);
            assertTrue(scopes.add(scope), "OAuth Scope 重复: " + scope);
            assertTrue(serviceIds.contains(requiredText(entry, "ownerServiceId", scopeRegistryPath)),
                    scope + " 使用了未登记 ownerServiceId");
            if (entry.path("gatewayRouteAllowed").asBoolean()) {
                publicScopes.add(scope);
            }
        }
        assertEquals(Set.of("runtime:read", "runtime:quota:write"), publicScopes,
                "只有 MVP Runtime Scope 可以用于公网 Service route");

        String oauthScopeSource = Files.readString(
                REPOSITORY.resolve("saas-forge-services/iam-service/src/main/java/io/saas/forge/iam/domain/client/OAuthScope.java"),
                StandardCharsets.UTF_8);
        Matcher scopeMatcher = Pattern.compile("\\(\"([a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)+)\"\\)")
                .matcher(oauthScopeSource);
        Set<String> iamScopes = new HashSet<>();
        while (scopeMatcher.find()) {
            iamScopes.add(scopeMatcher.group(1));
        }
        assertEquals(scopes, iamScopes, "IAM OAuthScope 必须与 OAuth Scope Registry 精确一致");
    }

    @Test
    void publicRestOperationsHaveOneServiceOwnerAndMatchingServerGeneration() throws Exception {
        List<OpenApiOperation> operations = parseOpenApiOperations(
                REPOSITORY.resolve("saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml"));
        assertFalse(operations.isEmpty(), "OpenAPI 根契约至少需要一个 operation");

        Map<String, Set<String>> tagsByService = new HashMap<>();
        for (OpenApiOperation operation : operations) {
            assertEquals(1, operation.tags().size(), operation.displayName() + " 必须恰有一个 tag");
            assertEquals(1, operation.ownerDeclarations(), operation.displayName()
                    + " 必须恰有一个 x-saas.forge-service");
            assertTrue(SERVICE_ARTIFACTS.contains(operation.owner()), operation.displayName()
                    + " 使用了未知的 x-saas.forge-service: " + operation.owner());

            String tag = operation.tags().iterator().next();
            String expectedOwner = OPENAPI_TAG_OWNERS.get(tag);
            assertNotNull(expectedOwner, operation.displayName() + " 使用了未登记归属的 tag: " + tag);
            assertEquals(expectedOwner, operation.owner(), operation.displayName()
                    + " 的 x-saas.forge-service 必须与 tag 的生成归属一致");
            tagsByService.computeIfAbsent(operation.owner(), ignored -> new LinkedHashSet<>()).add(tag);
        }

        for (Map.Entry<String, Set<String>> entry : tagsByService.entrySet()) {
            Path pom = REPOSITORY.resolve("saas-forge-services").resolve(entry.getKey()).resolve("pom.xml");
            String pomSource = Files.readString(pom, StandardCharsets.UTF_8);
            for (String tag : entry.getValue()) {
                String generatorName = OPENAPI_TAG_GENERATOR_NAMES.get(tag);
                assertNotNull(generatorName, "缺少 tag " + tag + " 的 OpenAPI Generator 名称");
                assertTrue(pomSource.contains(generatorName), pom + " 必须为 tag " + tag + " 生成服务端接口");
            }
        }
    }

    @Test
    void routeCatalogMatchesOpenApiOwnershipAndCredentialSecurity() throws Exception {
        List<OpenApiOperation> operations = parseOpenApiOperations(
                REPOSITORY.resolve("saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml"));
        operations.addAll(parseOpenApiOperations(
                REPOSITORY.resolve("saas-forge-contracts/saas-forge-openapi-contracts/v2.yaml")));
        Map<String, HttpRouteCatalog.Route> routes = new HashMap<>();
        for (HttpRouteCatalog.Route route : HttpRouteCatalogLoader.load().routes()) {
            if (isAcceptanceRoute(route)) {
                continue;
            }
            assertTrue(routes.put(route.operationId(), route) == null,
                    "Route Catalog 重复登记 operationId: " + route.operationId());
        }

        assertEquals(operations.size(), routes.size(), "Route Catalog 路由数量必须与 OpenAPI operation 数量一致");
        for (OpenApiOperation operation : operations) {
            assertNotNull(operation.operationId(), operation.displayName() + " 缺少 operationId");
            HttpRouteCatalog.Route route = routes.get(operation.operationId());
            assertNotNull(route, operation.displayName() + " 未登记到 Route Catalog");
            assertEquals(operation.method().toUpperCase(), route.method().name(),
                    operation.operationId() + " 的 HTTP method 与 OpenAPI 不一致");
            assertEquals(operation.path(), route.path(),
                    operation.operationId() + " 的 path 与 OpenAPI 不一致");
            assertEquals(operation.owner(), route.serviceId(),
                    operation.operationId() + " 的 serviceId 与 OpenAPI 不一致");
            assertEquals(operation.credentialRequirement(), route.credentialRequirement().name(),
                    operation.operationId() + " 的凭据分类与 OpenAPI 不一致");
        }
    }

    private static boolean isAcceptanceRoute(HttpRouteCatalog.Route route) {
        return route.path().startsWith("/__test/");
    }

    /**
     * 服务产出的事件必须在事件工程注册表中登记。只校验"产出 → 登记"这一个方向：注册表允许登记
     * 由运行时拼接类型的事件，例如 {@code EntitlementEventFactory} 用
     * {@code "com.saas.forge.plan." + action + ".v1"} 组装类型，静态扫描看不到常量，
     * 因此反方向会误报。这同时是本门禁的盲区：动态拼接的类型不会在此被校验。
     */
    @Test
    void producedEventTypesAreRegisteredInEngineeringRegistry() throws Exception {
        JsonNode registry = readJson(REPOSITORY.resolve(
                "saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json"));
        Set<String> registeredEvents = new HashSet<>();
        for (JsonNode entry : registry.path("entries")) {
            registeredEvents.add(requiredText(entry, "type",
                    Path.of("saas-forge-contracts/saas-forge-event-contracts/engineering-registry.json")));
        }
        assertFalse(registeredEvents.isEmpty(), "事件工程注册表不得为空");

        Map<String, String> producedEvents = new TreeMap<>();
        for (String serviceArtifact : SERVICE_ARTIFACTS) {
            Path serviceRoot = REPOSITORY.resolve("saas-forge-services").resolve(serviceArtifact).resolve("src/main/java");
            for (Path javaFile : filesUnder(serviceRoot, ".java")) {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                Matcher matcher = DECLARED_EVENT_TYPE.matcher(source);
                while (matcher.find()) {
                    producedEvents.putIfAbsent(matcher.group(1), REPOSITORY.relativize(javaFile).toString());
                }
            }
        }
        assertFalse(producedEvents.isEmpty(), "未扫描到任何 EVENT_TYPE 常量声明，必须复核扫描范围");

        Set<String> unregisteredEvents = new TreeSet<>(producedEvents.keySet());
        unregisteredEvents.removeAll(registeredEvents);
        assertTrue(unregisteredEvents.isEmpty(),
                "以下事件由服务产出但未登记到 engineering-registry.json，必须补登记或删除该 EVENT_TYPE 常量: "
                        + unregisteredEvents.stream()
                                .map(type -> type + " (" + producedEvents.get(type) + ")")
                                .toList());
    }

    @Test
    void handWrittenControllersCannotDefinePublicHttpRoutes() throws Exception {
        for (String serviceArtifact : SERVICE_ARTIFACTS) {
            Path serviceRoot = REPOSITORY.resolve("saas-forge-services").resolve(serviceArtifact).resolve("src/main/java");
            for (Path javaFile : filesUnder(serviceRoot, ".java")) {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                assertFalse(SPRING_HTTP_MAPPING.matcher(source).find(), javaFile
                        + " 不得手写 Spring HTTP 路由；公开路由必须来自生成的 OpenAPI 接口");
                if (REST_CONTROLLER.matcher(source).find()) {
                    assertTrue(GENERATED_API_IMPLEMENTATION.matcher(source).find(), javaFile
                            + " 的 @RestController 必须实现生成的 OpenAPI Api 接口");
                }
            }
        }
    }

    @Test
    void iamServiceTokenReceiversDependOnlyOnComposedAuthorizer() throws Exception {
        Path receiverRoot = REPOSITORY.resolve(
                "saas-forge-services/iam-service/src/main/java/io/saas/forge/iam/infrastructure/grpc");
        List<Path> interceptors = filesUnder(receiverRoot, "ServerInterceptor.java");
        assertEquals(4, interceptors.size(), "IAM Service Token gRPC 接收端清单发生变化时必须显式评审");
        for (Path interceptor : interceptors) {
            String source = Files.readString(interceptor, StandardCharsets.UTF_8);
            assertTrue(source.contains("ServiceAccessTokenAuthorizer"),
                    interceptor + " 必须依赖组合 ServiceAccessTokenAuthorizer");
            assertFalse(source.contains("ServiceAccessTokenVerifier")
                            || source.contains("ServiceAccessTokenSignatureVerifier"),
                    interceptor + " 不得直接依赖纯 Service Access Token 验签器");
        }
    }

    @Test
    void tenantAccessServiceTokenReceiversDependOnlyOnComposedAuthorizer() throws Exception {
        Path receiverRoot = REPOSITORY.resolve(
                "saas-forge-services/tenant-access-service/src/main/java/io/saas/forge/tenantaccess/infrastructure/grpc");
        List<Path> interceptors = filesUnder(receiverRoot, "ServerInterceptor.java");
        assertEquals(2, interceptors.size(), "Tenant Access Service Token gRPC 接收端清单发生变化时必须显式评审");
        for (Path interceptor : interceptors) {
            String source = Files.readString(interceptor, StandardCharsets.UTF_8);
            assertTrue(source.contains("ServiceAccessTokenAuthorizer"),
                    interceptor + " 必须依赖组合 ServiceAccessTokenAuthorizer");
            assertFalse(source.contains("ServiceAccessTokenVerifier")
                            || source.contains("ServiceAccessTokenSignatureVerifier"),
                    interceptor + " 不得直接依赖纯 Service Access Token 验签器");
        }
    }

    @Test
    void entitlementServiceTokenReceiversDependOnlyOnComposedAuthorizer() throws Exception {
        Path receiverRoot = REPOSITORY.resolve(
                "saas-forge-services/entitlement-service/src/main/java/io/saas/forge/entitlement/infrastructure/grpc");
        List<Path> interceptors = filesUnder(receiverRoot, "ServerInterceptor.java");
        assertEquals(1, interceptors.size(), "Entitlement Service Token gRPC 接收端清单发生变化时必须显式评审");
        for (Path interceptor : interceptors) {
            String source = Files.readString(interceptor, StandardCharsets.UTF_8);
            assertTrue(source.contains("ServiceAccessTokenAuthorizer"),
                    interceptor + " 必须依赖组合 ServiceAccessTokenAuthorizer");
            assertFalse(source.contains("ServiceAccessTokenVerifier")
                            || source.contains("ServiceAccessTokenSignatureVerifier"),
                    interceptor + " 不得直接依赖纯 Service Access Token 验签器");
        }
    }

    @Test
    void springRepositoriesRemainProxyable() throws Exception {
        for (String serviceArtifact : SERVICE_ARTIFACTS) {
            Path serviceRoot = REPOSITORY.resolve("saas-forge-services").resolve(serviceArtifact).resolve("src/main/java");
            for (Path javaFile : filesUnder(serviceRoot, ".java")) {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                assertFalse(FINAL_REPOSITORY_CLASS.matcher(source).find(), javaFile
                        + " 的 Spring Repository 不能声明为 final，否则事务代理无法启动");
            }
        }
    }

    @Test
    void loggingSchemaAndPolicyStayAligned() throws Exception {
        JsonNode schema = readJson(REPOSITORY.resolve("saas-forge-contracts/logging/application-log.schema.json"));
        JsonNode policy = readJson(REPOSITORY.resolve("saas-forge-contracts/logging/policy.json"));

        Set<String> required = textSet(schema.path("required"));
        assertEquals(Set.of("timestamp", "level", "service", "environment", "event", "message", "schemaVersion"),
                required, "日志基础必填字段发生变化时必须显式评审");
        assertFalse(schema.path("additionalProperties").asBoolean(true), "日志顶层必须使用字段白名单");
        assertEquals(policy.path("schemaVersion").asInt(), schema.at("/properties/schemaVersion/const").asInt(),
                "日志策略与 Schema 版本必须一致");

        Set<String> schemaFields = new LinkedHashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(schemaFields::add);
        assertEquals(schemaFields, textSet(policy.path("topLevelFieldWhitelist")),
                "日志策略白名单必须与 Schema 顶层字段一致");

        Set<String> sensitivePatterns = textSet(policy.path("sensitiveFieldPatterns"));
        assertTrue(sensitivePatterns.containsAll(Set.of(
                "authorization", "clientsecret", "cookie", "email", "password", "privatekey", "secret", "token")),
                "日志策略缺少关键敏感字段模式");
        assertFalse(policy.at("/production/debugEnabledByDefault").asBoolean(true),
                "生产环境不得默认启用 DEBUG");
        assertFalse(policy.at("/production/traceEnabledByDefault").asBoolean(true),
                "生产环境不得默认启用 TRACE");
        assertTrue(policy.at("/production/traceConsistentSampling").asBoolean(false),
                "同一 Trace 必须保持一致采样");
        assertEquals(Set.of("ERROR", "WARN"), textSet(policy.at("/sampling/neverSampleLevels")),
                "ERROR 与 WARN 不得采样丢弃");
        assertTrue(textSet(policy.at("/sampling/neverSampleEventPrefixes")).contains("security."),
                "安全事件不得采样丢弃");

        Set<String> retentionClasses = textSet(policy.path("retentionClasses"));
        Set<String> events = new HashSet<>();
        for (JsonNode eventPolicy : policy.path("eventPolicies")) {
            String event = requiredText(eventPolicy, "event", Path.of("saas-forge-contracts/logging/policy.json"));
            double sampleRate = eventPolicy.path("sampleRate").asDouble(-1);
            String minimumLevel = requiredText(eventPolicy, "minimumLevel", Path.of("saas-forge-contracts/logging/policy.json"));
            assertTrue(LOG_EVENT.matcher(event).matches(), "日志事件名不合法: " + event);
            assertTrue(events.add(event), "日志事件策略重复: " + event);
            assertTrue(sampleRate >= 0 && sampleRate <= 1, event + " 的采样率必须在 0 到 1 之间");
            assertTrue(retentionClasses.contains(eventPolicy.path("retentionClass").asText()),
                    event + " 使用了未登记的保留类别");
            if (minimumLevel.equals("ERROR") || minimumLevel.equals("WARN") || event.startsWith("security.")) {
                assertEquals(1.0, sampleRate, 0.0, event + " 不得采样丢弃");
            }
        }
    }

    @Test
    void runtimeSqlOnlyLivesInMapperXml() throws Exception {
        for (Path root : List.of(
                REPOSITORY.resolve("saas-forge-services"),
                REPOSITORY.resolve("gateway"),
                REPOSITORY.resolve("saas-forge-sdk"))) {
            for (Path javaFile : filesUnder(root, ".java")) {
                String source = Files.readString(javaFile, StandardCharsets.UTF_8);
                assertFalse(ANNOTATED_SQL.matcher(source).find(),
                        javaFile + " 使用了注解或 Provider SQL；运行时 SQL 必须维护在 Mapper XML");
            }
        }
    }

    @Test
    void returningDmlSelectsDeclareMutationSemantics() throws Exception {
        for (Path xmlFile : filesUnder(REPOSITORY, "Mapper.xml")) {
            Path relative = REPOSITORY.relativize(xmlFile);
            if (owningPersistenceModule(relative) == null) {
                continue;
            }
            String xml = Files.readString(xmlFile, StandardCharsets.UTF_8);
            Matcher selectMatcher = MAPPER_SELECT.matcher(xml);
            while (selectMatcher.find()) {
                String attributes = selectMatcher.group(1);
                String sql = selectMatcher.group(2);
                if (!DML_RETURNING.matcher(sql).find()) {
                    continue;
                }
                String statementId = matchRequired(attributes, Pattern.compile("\\bid=\"([^\"]+)\""), xmlFile,
                        "RETURNING DML 的 select 必须声明 statement id");
                assertTrue(AFFECT_DATA_TRUE.matcher(attributes).find(),
                        xmlFile + " 的 " + statementId + " 必须声明 affectData=\"true\"");
                assertTrue(FLUSH_CACHE_TRUE.matcher(attributes).find(),
                        xmlFile + " 的 " + statementId + " 必须声明 flushCache=\"true\"");
            }
        }
    }

    @Test
    void persistenceArtifactsStayInsideOwningService() throws Exception {
        Map<String, Set<String>> migrationVersions = new HashMap<>();
        for (Path file : repositoryFiles()) {
            Path relative = REPOSITORY.relativize(file);
            String normalized = relative.toString().replace('\\', '/');

            if (normalized.contains("src/main/resources/db/migration/")) {
                String owner = owningPersistenceModule(relative);
                assertNotNull(owner, file + " 的 Flyway 迁移不属于任何领域服务或官方 Example");
                String fileName = file.getFileName().toString();
                Matcher versioned = VERSIONED_MIGRATION.matcher(fileName);
                Matcher repeatable = REPEATABLE_MIGRATION.matcher(fileName);
                assertTrue(versioned.matches() || repeatable.matches(), file + " 不符合 Flyway 文件命名规范");
                if (versioned.matches()) {
                    assertTrue(migrationVersions.computeIfAbsent(owner, ignored -> new HashSet<>())
                                    .add(versioned.group(1)),
                            owner + " 存在重复 Flyway 版本 " + versioned.group(1));
                } else {
                    String sql = Files.readString(file, StandardCharsets.UTF_8);
                    assertFalse(REPEATABLE_FORBIDDEN_SQL.matcher(sql).find(),
                            file + " 的 Repeatable Migration 不得修改表结构或业务数据");
                }
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(CROSS_DATABASE_ACCESS.matcher(sql).find(),
                        file + " 不得使用 FDW、dblink 或其他跨数据库访问机制");
            }

            if (file.getFileName().toString().endsWith("Mapper.xml")) {
                String owner = owningPersistenceModule(relative);
                assertNotNull(owner, file + " 的 Mapper XML 不属于任何领域服务或官方 Example");
                String xml = Files.readString(file, StandardCharsets.UTF_8);
                Matcher namespace = MAPPER_NAMESPACE.matcher(xml);
                assertTrue(namespace.find(), file + " 必须声明 Mapper namespace");
                if (SERVICE_PACKAGES.containsKey(owner)) {
                    assertTrue(namespace.group(1).startsWith(SERVICE_PACKAGES.get(owner) + "."),
                            file + " 的 namespace 必须属于服务包 " + SERVICE_PACKAGES.get(owner));
                }
            }

            if (isPublicPersistenceType(relative)) {
                throw new AssertionError(file + " 在公共或接入模块中暴露了持久化类型");
            }
        }
    }

    @Test
    void mapperInterfacesAndXmlStatementsStayAligned() throws Exception {
        Map<String, Path> xmlByNamespace = new HashMap<>();
        Map<String, Set<String>> statementsByNamespace = new HashMap<>();

        for (Path xmlFile : filesUnder(REPOSITORY, "Mapper.xml")) {
            Path relative = REPOSITORY.relativize(xmlFile);
            if (owningPersistenceModule(relative) == null) {
                continue;
            }
            String xml = Files.readString(xmlFile, StandardCharsets.UTF_8);
            Matcher namespaceMatcher = MAPPER_NAMESPACE.matcher(xml);
            assertTrue(namespaceMatcher.find(), xmlFile + " 必须声明 Mapper namespace");
            String namespace = namespaceMatcher.group(1);
            assertTrue(xmlByNamespace.put(namespace, xmlFile) == null, "Mapper namespace 重复: " + namespace);

            Set<String> statementIds = new LinkedHashSet<>();
            Matcher statementMatcher = MAPPER_STATEMENT.matcher(xml);
            while (statementMatcher.find()) {
                assertTrue(statementIds.add(statementMatcher.group(1)),
                        xmlFile + " 存在重复 statement id " + statementMatcher.group(1));
            }
            statementsByNamespace.put(namespace, statementIds);
        }

        for (Path javaFile : filesUnder(REPOSITORY, "Mapper.java")) {
            Path relative = REPOSITORY.relativize(javaFile);
            if (owningPersistenceModule(relative) == null) {
                continue;
            }
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            String packageName = matchRequired(source, Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+);"), javaFile,
                    "Mapper 接口必须声明 package");
            String namespace = packageName + "." + javaFile.getFileName().toString().replace(".java", "");
            assertTrue(xmlByNamespace.containsKey(namespace), javaFile + " 缺少 namespace 为 " + namespace + " 的 Mapper XML");

            Set<String> methodNames = new LinkedHashSet<>();
            Matcher methodMatcher = MAPPER_METHOD.matcher(source);
            while (methodMatcher.find()) {
                assertTrue(methodNames.add(methodMatcher.group(1)),
                        javaFile + " 不得声明同名重载 Mapper 方法 " + methodMatcher.group(1));
            }
            assertEquals(methodNames, statementsByNamespace.get(namespace),
                    javaFile + " 的方法必须与 Mapper XML statement id 一一对应");
        }

        Set<String> javaNamespaces = new HashSet<>();
        for (Path javaFile : filesUnder(REPOSITORY, "Mapper.java")) {
            Path relative = REPOSITORY.relativize(javaFile);
            if (owningPersistenceModule(relative) == null) {
                continue;
            }
            String source = Files.readString(javaFile, StandardCharsets.UTF_8);
            String packageName = matchRequired(source, Pattern.compile("(?m)^package\\s+([A-Za-z0-9_.]+);"), javaFile,
                    "Mapper 接口必须声明 package");
            javaNamespaces.add(packageName + "." + javaFile.getFileName().toString().replace(".java", ""));
        }
        assertEquals(javaNamespaces, xmlByNamespace.keySet(), "每个 Mapper XML 必须存在唯一对应的 Mapper 接口");
    }

    @Test
    void servicesDoNotDependOnOtherServiceImplementations() throws Exception {
        for (String serviceArtifact : SERVICE_ARTIFACTS) {
            Path pomFile = REPOSITORY.resolve("saas-forge-services").resolve(serviceArtifact).resolve("pom.xml");
            Document pom = parseXml(pomFile);
            NodeList dependencies = pom.getElementsByTagName("dependency");
            for (int index = 0; index < dependencies.getLength(); index++) {
                org.w3c.dom.Node dependency = dependencies.item(index);
                String groupId = childText(dependency, "groupId");
                String artifactId = childText(dependency, "artifactId");
                assertFalse("io.github.crane0927".equals(groupId) && SERVICE_ARTIFACTS.contains(artifactId),
                        pomFile + " 不得依赖领域服务实现 " + artifactId);
            }
        }
    }

    @Test
    void governanceDocumentsAndContractsAreVersionedTogether() {
        for (String relative : List.of(
                "docs/11-database-design.md",
                "docs/19-redis-key-registry.md",
                "docs/20-application-logging.md",
                "docs/adr/0011-versioned-data-cache-and-logging-standards.md",
                "saas-forge-contracts/redis/registry.schema.json",
                "saas-forge-contracts/logging/application-log.schema.json",
                "saas-forge-contracts/logging/policy.json")) {
            assertTrue(Files.isRegularFile(REPOSITORY.resolve(relative)), "缺少治理文件 " + relative);
        }
    }

    private static JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(path.toFile());
    }

    private static String requiredText(JsonNode node, String field, Path source) {
        assertTrue(node.has(field) && node.path(field).isTextual() && !node.path(field).asText().isBlank(),
                source + " 缺少非空文本字段 " + field);
        return node.path(field).asText();
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        assertTrue(array.isArray(), "预期 JSON 数组，实际为 " + array);
        array.forEach(value -> {
            assertTrue(value.isTextual(), "数组值必须是字符串: " + value);
            assertTrue(values.add(value.asText()), "数组值重复: " + value.asText());
        });
        return values;
    }

    private static List<OpenApiOperation> parseOpenApiOperations(Path spec) throws IOException {
        String path = null;
        String method = null;
        Set<String> tags = Set.of();
        String owner = null;
        String operationId = null;
        int ownerDeclarations = 0;
        boolean explicitSecurity = false;
        boolean userBearer = false;
        boolean platformRefreshCookie = false;
        boolean tenantRefreshCookie = false;
        boolean consoleSlotCookie = false;
        boolean consoleRefreshCookie = false;
        boolean oauthClientBasic = false;
        boolean anonymousAlternative = false;
        boolean readingSecurity = false;
        List<OpenApiOperation> operations = new ArrayList<>();

        for (String line : Files.readAllLines(spec, StandardCharsets.UTF_8)) {
            Matcher pathMatcher = OPENAPI_PATH.matcher(line);
            if (pathMatcher.matches()) {
                if (method != null) {
                    operations.add(new OpenApiOperation(
                            path, method, tags, owner, operationId, ownerDeclarations,
                            credentialRequirement(explicitSecurity, userBearer,
                                    platformRefreshCookie, tenantRefreshCookie,
                                    oauthClientBasic, anonymousAlternative, consoleSlotCookie, consoleRefreshCookie)));
                    method = null;
                    tags = Set.of();
                    owner = null;
                    operationId = null;
                    ownerDeclarations = 0;
                    explicitSecurity = false;
                    userBearer = false;
                    platformRefreshCookie = false;
                    tenantRefreshCookie = false;
                    consoleSlotCookie = false;
                    consoleRefreshCookie = false;
                    oauthClientBasic = false;
                    anonymousAlternative = false;
                    readingSecurity = false;
                }
                path = pathMatcher.group(1);
                continue;
            }

            Matcher methodMatcher = OPENAPI_METHOD.matcher(line);
            if (methodMatcher.matches()) {
                if (method != null) {
                    operations.add(new OpenApiOperation(
                            path, method, tags, owner, operationId, ownerDeclarations,
                            credentialRequirement(explicitSecurity, userBearer,
                                    platformRefreshCookie, tenantRefreshCookie,
                                    oauthClientBasic, anonymousAlternative, consoleSlotCookie, consoleRefreshCookie)));
                }
                method = methodMatcher.group(1);
                tags = Set.of();
                owner = null;
                operationId = null;
                ownerDeclarations = 0;
                explicitSecurity = false;
                userBearer = false;
                platformRefreshCookie = false;
                tenantRefreshCookie = false;
                consoleSlotCookie = false;
                consoleRefreshCookie = false;
                oauthClientBasic = false;
                anonymousAlternative = false;
                readingSecurity = false;
                continue;
            }

            if (method == null) {
                continue;
            }
            if (readingSecurity) {
                if (line.startsWith("        - ") || line.startsWith("          Console")) {
                    userBearer |= line.contains("UserBearerAuth");
                    platformRefreshCookie |= line.contains("PlatformRefreshCookieAuth");
                    tenantRefreshCookie |= line.contains("TenantRefreshCookieAuth");
                    consoleSlotCookie |= line.contains("ConsoleSlotCookieAuth");
                    consoleRefreshCookie |= line.contains("ConsoleRefreshCookieAuth");
                    oauthClientBasic |= line.contains("OAuthClientBasic");
                    anonymousAlternative |= line.trim().equals("- {}");
                    continue;
                }
                readingSecurity = false;
            }
            Matcher operationIdMatcher = OPENAPI_OPERATION_ID.matcher(line);
            if (operationIdMatcher.matches()) {
                operationId = operationIdMatcher.group(1);
                continue;
            }
            Matcher tagsMatcher = OPENAPI_TAGS.matcher(line);
            if (tagsMatcher.matches()) {
                Set<String> parsedTags = new LinkedHashSet<>();
                for (String tag : tagsMatcher.group(1).split(",")) {
                    parsedTags.add(tag.trim());
                }
                tags = parsedTags;
                continue;
            }
            Matcher ownerMatcher = OPENAPI_SERVICE_OWNER.matcher(line);
            if (ownerMatcher.matches()) {
                owner = ownerMatcher.group(1);
                ownerDeclarations++;
                continue;
            }
            Matcher securityMatcher = OPENAPI_SECURITY.matcher(line);
            if (securityMatcher.matches()) {
                explicitSecurity = true;
                String inlineSecurity = securityMatcher.group(1);
                userBearer |= inlineSecurity.contains("UserBearerAuth");
                platformRefreshCookie |= inlineSecurity.contains("PlatformRefreshCookieAuth");
                tenantRefreshCookie |= inlineSecurity.contains("TenantRefreshCookieAuth");
                consoleSlotCookie |= inlineSecurity.contains("ConsoleSlotCookieAuth");
                consoleRefreshCookie |= inlineSecurity.contains("ConsoleRefreshCookieAuth");
                oauthClientBasic |= inlineSecurity.contains("OAuthClientBasic");
                anonymousAlternative |= inlineSecurity.contains("{}");
                readingSecurity = inlineSecurity.isBlank();
            }
        }
        if (method != null) {
            operations.add(new OpenApiOperation(
                    path, method, tags, owner, operationId, ownerDeclarations,
                    credentialRequirement(explicitSecurity, userBearer,
                            platformRefreshCookie, tenantRefreshCookie,
                            oauthClientBasic, anonymousAlternative, consoleSlotCookie, consoleRefreshCookie)));
        }
        return operations;
    }

    private static String credentialRequirement(
            boolean explicitSecurity,
            boolean userBearer,
            boolean platformRefreshCookie,
            boolean tenantRefreshCookie,
            boolean oauthClientBasic,
            boolean anonymousAlternative,
            boolean consoleSlotCookie,
            boolean consoleRefreshCookie) {
        if (!explicitSecurity) {
            return "ANONYMOUS";
        }
        if (consoleSlotCookie) {
            return consoleRefreshCookie ? "CONSOLE_SESSION_REQUIRED" : "CONSOLE_SLOT_REQUIRED";
        }
        if (platformRefreshCookie && tenantRefreshCookie) {
            return "BROWSER_SESSION_SLOT_REQUIRED";
        }
        if (platformRefreshCookie) {
            return "PLATFORM_REFRESH_COOKIE_REQUIRED";
        }
        if (tenantRefreshCookie) {
            return "TENANT_REFRESH_COOKIE_REQUIRED";
        }
        if (oauthClientBasic) {
            return "OAUTH_CLIENT_BASIC_REQUIRED";
        }
        if (userBearer) {
            return anonymousAlternative ? "USER_OPTIONAL" : "USER_REQUIRED";
        }
        return "ANONYMOUS";
    }

    private static List<Path> filesUnder(Path root, String suffix) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(suffix))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
        }
    }

    private static Set<String> registeredServiceArtifacts() {
        try {
            JsonNode entries = JSON.readTree(
                    REPOSITORY.resolve("saas-forge-contracts/services/engineering-registry.json").toFile()).path("entries");
            Set<String> artifacts = new LinkedHashSet<>();
            for (JsonNode entry : entries) {
                if (entry.path("modulePath").asText().startsWith("saas-forge-services/")) {
                    artifacts.add(entry.path("artifactId").asText());
                }
            }
            return Set.copyOf(artifacts);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static List<Path> repositoryFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(REPOSITORY)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(REPOSITORY.resolve(".git")))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
        }
    }

    private static String owningPersistenceModule(Path relative) {
        if (relative.getNameCount() < 2 || !relative.getName(0).toString().equals("saas-forge-services")) {
            if (relative.getNameCount() >= 2 && relative.getName(0).toString().equals("examples")) {
                return "examples/" + relative.getName(1);
            }
            return null;
        }
        String service = relative.getName(1).toString();
        return SERVICE_ARTIFACTS.contains(service) ? service : null;
    }

    private static boolean isPublicPersistenceType(Path relative) {
        if (relative.getNameCount() < 2 || owningPersistenceModule(relative) != null) {
            return false;
        }
        String normalized = relative.toString().replace('\\', '/');
        if (!normalized.contains("/src/main/") && !normalized.startsWith("src/main/")) {
            return false;
        }
        String fileName = relative.getFileName().toString();
        return fileName.endsWith("Mapper.java")
                || fileName.endsWith("Mapper.xml")
                || fileName.endsWith("DO.java")
                || fileName.endsWith("PO.java")
                || fileName.endsWith("PersistenceEntity.java")
                || fileName.endsWith("DatabaseEntity.java");
    }

    private static String matchRequired(String source, Pattern pattern, Path path, String message) {
        Matcher matcher = pattern.matcher(source);
        assertTrue(matcher.find(), path + " " + message);
        return matcher.group(1);
    }

    private static Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static String childText(org.w3c.dom.Node parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            org.w3c.dom.Node child = children.item(index);
            if (child.getNodeName().equals(name)) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    private record OpenApiOperation(
            String path,
            String method,
            Set<String> tags,
            String owner,
            String operationId,
            int ownerDeclarations,
            String credentialRequirement) {

        private String displayName() {
            return method.toUpperCase() + " " + path;
        }
    }

}
