import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 从正式 OpenAPI、Service Registry 与 Scope Registry 生成唯一的版本化 Route Catalog。 */
public final class HttpRouteCatalogGenerator {
    private static final int SCHEMA_VERSION = 1;
    private static final Pattern SERVICE_ID = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern OWNER = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern SCOPE = Pattern.compile("^[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)+$");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^/{}]+}");
    private static final Set<String> CLIENT_TYPES = Set.of("RESERVED_SERVICE", "RUNTIME_SERVICE");
    private static final Set<String> USAGES = Set.of("INTERNAL", "RUNTIME");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private HttpRouteCatalogGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("需要仓库根目录、Route Catalog 输出路径和可选测试 overlay");
        }
        Path repository = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]);
        ServiceRegistry services = JSON.readValue(
                repository.resolve("saas-forge-contracts/services/engineering-registry.json").toFile(), ServiceRegistry.class);
        ScopeRegistry scopes = JSON.readValue(
                repository.resolve("saas-forge-contracts/security/oauth-scope-registry.json").toFile(), ScopeRegistry.class);
        Map<String, ServiceEntry> servicesById = new HashMap<>(validateServices(repository, services));
        Path registryOverlay = optionalPath(args[2]);
        Path openApiOverlay = optionalPath(args[3]);
        require((registryOverlay == null) == (openApiOverlay == null),
                "测试 Registry 与 OpenAPI overlay 必须同时提供");
        if (registryOverlay != null) {
            mergeTestServices(repository, registryOverlay, servicesById);
        }
        Map<String, ScopeEntry> scopesByName = validateScopes(scopes, servicesById);
        OpenAPI openApi = parseOpenApi(repository.resolve("saas-forge-contracts/saas-forge-openapi-contracts/v1.yaml"));
        validateSecuritySchemes(openApi, scopesByName);
        List<Route> routes = new ArrayList<>(generateRoutes(openApi, servicesById, scopesByName));
        OpenAPI consoleApi = parseOpenApi(repository.resolve("saas-forge-contracts/saas-forge-openapi-contracts/v2.yaml"));
        validateConsoleSecuritySchemes(consoleApi);
        routes.addAll(generateRoutes(consoleApi, servicesById, scopesByName));
        if (openApiOverlay != null) {
            OpenAPI testOpenApi = parseOpenApi(openApiOverlay);
            validateSecuritySchemes(testOpenApi, scopesByName);
            routes.addAll(generateRoutes(testOpenApi, servicesById, scopesByName));
            validateDistinctRoutes(routes);
            routes.sort(Comparator.comparing(Route::method)
                    .thenComparing(Route::path)
                    .thenComparing(Route::operationId));
        }
        validateDistinctRoutes(routes);
        routes.sort(Comparator.comparing(Route::method).thenComparing(Route::path).thenComparing(Route::operationId));
        validateRouteEligibility(routes, servicesById);
        Files.createDirectories(output.getParent());
        JSON.writeValue(output.toFile(), new Catalog(SCHEMA_VERSION, routes));
    }

    private static Path optionalPath(String value) {
        return "-".equals(value) ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static void mergeTestServices(
            Path repository, Path registryOverlay, Map<String, ServiceEntry> services) throws IOException {
        ServiceRegistry overlay = JSON.readValue(registryOverlay.toFile(), ServiceRegistry.class);
        require(overlay.registryVersion() == 1 && overlay.entries() != null && !overlay.entries().isEmpty(),
                "测试 Service Registry overlay 版本或 entries 非法");
        for (ServiceEntry entry : overlay.entries()) {
            require(entry != null
                            && matches(SERVICE_ID, entry.serviceId())
                            && matches(OWNER, entry.owner())
                            && entry.modulePath() != null
                            && entry.modulePath().startsWith("test-support/")
                            && matches(SERVICE_ID, entry.artifactId())
                            && matches(SERVICE_ID, entry.nacosServiceName())
                            && entry.deployable()
                            && entry.gatewayRouteTargetAllowed(),
                    "测试 Service Registry overlay entry 字段非法");
            require(!services.containsKey(entry.serviceId()),
                    "测试 serviceId 与生产 Registry 冲突: " + entry.serviceId());
            require(services.values().stream().noneMatch(existing ->
                            existing.owner().equals(entry.owner())
                                    || existing.modulePath().equals(entry.modulePath())
                                    || existing.artifactId().equals(entry.artifactId())
                                    || existing.nacosServiceName().equals(entry.nacosServiceName())),
                    "测试 Service Registry overlay 与生产字段冲突: " + entry.serviceId());
            Path module = repository.resolve(entry.modulePath()).normalize();
            require(module.startsWith(repository.resolve("test-support").normalize())
                            && Files.isRegularFile(module.resolve("pom.xml")),
                    "测试接收端 module 不存在: " + entry.modulePath());
            String pom = Files.readString(module.resolve("pom.xml"), StandardCharsets.UTF_8);
            String application = Files.readString(
                    module.resolve("src/main/resources/application.yaml"), StandardCharsets.UTF_8);
            require(pom.contains("<artifactId>" + entry.artifactId() + "</artifactId>"),
                    entry.serviceId() + " artifactId 与测试 module 不一致");
            require(application.contains("    name: " + entry.serviceId())
                            && application.contains("        service: " + entry.nacosServiceName()),
                    entry.serviceId() + " 与测试 Spring/Nacos service name 不一致");
            services.put(entry.serviceId(), entry);
        }
    }

    private static void validateDistinctRoutes(List<Route> routes) {
        Set<String> operationIds = new HashSet<>();
        Set<String> methodPaths = new HashSet<>();
        for (Route route : routes) {
            require(operationIds.add(route.operationId()), "operationId 重复: " + route.operationId());
            String normalizedPath = PATH_VARIABLE.matcher(route.path()).replaceAll("{}");
            require(methodPaths.add(route.method() + " " + normalizedPath),
                    "OpenAPI method/path 规范化冲突: " + route.method() + " " + normalizedPath);
        }
    }

    private static OpenAPI parseOpenApi(Path spec) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(spec.toUri().toString(), null, options);
        if (result.getOpenAPI() == null || result.getMessages() != null && !result.getMessages().isEmpty()) {
            throw new IllegalArgumentException("OpenAPI 语义解析失败: " + result.getMessages());
        }
        return result.getOpenAPI();
    }

    private static Map<String, ServiceEntry> validateServices(Path repository, ServiceRegistry registry)
            throws IOException {
        if (registry.registryVersion() != 1 || registry.entries() == null || registry.entries().isEmpty()) {
            throw new IllegalArgumentException("Service Registry 版本或 entries 非法");
        }
        Map<String, ServiceEntry> services = new HashMap<>();
        Set<String> owners = new HashSet<>();
        Set<String> modulePaths = new HashSet<>();
        Set<String> artifactIds = new HashSet<>();
        Set<String> nacosNames = new HashSet<>();
        String helm = Files.readString(
                repository.resolve("deploy/helm/nacos-production-contract.yaml"), StandardCharsets.UTF_8);
        String nacosAcl = gatewayDiscoveryPermissions(Files.readString(
                repository.resolve("deploy/compose/nacos-init.sh"), StandardCharsets.UTF_8));

        for (ServiceEntry entry : registry.entries()) {
            require(entry != null
                    && matches(SERVICE_ID, entry.serviceId())
                    && matches(OWNER, entry.owner())
                    && entry.modulePath() != null
                    && matches(SERVICE_ID, entry.artifactId())
                    && matches(SERVICE_ID, entry.nacosServiceName()), "Service Registry entry 字段非法");
            require(services.put(entry.serviceId(), entry) == null, "serviceId 重复: " + entry.serviceId());
            require(owners.add(entry.owner()), "Service owner 重复: " + entry.owner());
            require(modulePaths.add(entry.modulePath()), "modulePath 重复: " + entry.modulePath());
            require(artifactIds.add(entry.artifactId()), "artifactId 重复: " + entry.artifactId());
            require(nacosNames.add(entry.nacosServiceName()), "nacosServiceName 重复: " + entry.nacosServiceName());

            Path module = repository.resolve(entry.modulePath()).normalize();
            require(module.startsWith(repository) && Files.isRegularFile(module.resolve("pom.xml")),
                    "Service module 不存在: " + entry.modulePath());
            String pom = Files.readString(module.resolve("pom.xml"), StandardCharsets.UTF_8);
            require(pom.contains("<artifactId>" + entry.artifactId() + "</artifactId>"),
                    entry.serviceId() + " artifactId 与 module 不一致");
            String application = Files.readString(module.resolve("src/main/resources/application.yaml"),
                    StandardCharsets.UTF_8);
            require(application.contains("    name: " + entry.serviceId())
                            && application.contains("        service: " + entry.nacosServiceName()),
                    entry.serviceId() + " 与 Spring/Nacos service name 不一致");
            for (String environment : List.of("dev", "test", "staging", "prod")) {
                require(Files.isRegularFile(repository.resolve("deploy/nacos/" + environment + "/"
                                + entry.nacosServiceName() + ".yaml")),
                        entry.serviceId() + " 缺少 " + environment + " Nacos 资源");
            }
            if (entry.deployable()) {
                String compose = Files.readString(module.resolve("compose.yaml"), StandardCharsets.UTF_8);
                require(compose.contains("\n  " + entry.serviceId() + ":"),
                        entry.serviceId() + " 缺少所属模块的 Compose 部署清单");
                require(helm.contains("    " + entry.serviceId() + ":"),
                        entry.serviceId() + " 缺少 Helm Nacos 工作负载登记");
            } else {
                require(!entry.gatewayRouteTargetAllowed(), "不可部署服务不能成为 Gateway 路由目标");
            }
            if (!"gateway".equals(entry.serviceId())) {
                require(nacosAcl.contains("naming/" + entry.nacosServiceName() + ":r")
                                == entry.gatewayRouteTargetAllowed(),
                        entry.serviceId() + " 的 Gateway Nacos discovery ACL 与公网资格不一致");
            }
        }
        return Map.copyOf(services);
    }

    private static String gatewayDiscoveryPermissions(String nacosInit) {
        String startMarker = "# gateway-discovery-permissions: begin";
        String endMarker = "# gateway-discovery-permissions: end";
        int start = nacosInit.indexOf(startMarker);
        int end = nacosInit.indexOf(endMarker);
        require(start >= 0 && end > start, "Nacos ACL 缺少 Gateway discovery 权限块");
        return nacosInit.substring(start + startMarker.length(), end);
    }

    private static Map<String, ScopeEntry> validateScopes(
            ScopeRegistry registry, Map<String, ServiceEntry> services) {
        if (registry.registryVersion() != 1 || registry.entries() == null || registry.entries().isEmpty()) {
            throw new IllegalArgumentException("Scope Registry 版本或 entries 非法");
        }
        Map<String, ScopeEntry> scopes = new HashMap<>();
        for (ScopeEntry entry : registry.entries()) {
            require(entry != null
                            && matches(SCOPE, entry.scope())
                            && services.containsKey(entry.ownerServiceId())
                            && entry.clientTypes() != null
                            && !entry.clientTypes().isEmpty()
                            && new HashSet<>(entry.clientTypes()).size() == entry.clientTypes().size()
                            && CLIENT_TYPES.containsAll(entry.clientTypes())
                            && USAGES.contains(entry.usage())
                            && entry.description() != null
                            && !entry.description().isBlank(),
                    "Scope Registry entry 字段非法");
            require(scopes.put(entry.scope(), entry) == null, "Scope 重复: " + entry.scope());
            require(!entry.gatewayRouteAllowed() || "RUNTIME".equals(entry.usage())
                            && entry.clientTypes().equals(List.of("RUNTIME_SERVICE")),
                    entry.scope() + " 的公网资格与 usage/clientTypes 不一致");
            require(entry.gatewayRouteAllowed()
                            == Set.of("runtime:read", "runtime:quota:write").contains(entry.scope()),
                    entry.scope() + " 的公网资格不符合 MVP 固定边界");
        }
        return Map.copyOf(scopes);
    }

    private static void validateSecuritySchemes(OpenAPI openApi, Map<String, ScopeEntry> scopes) {
        Map<String, SecurityScheme> schemes = openApi.getComponents() == null
                ? null : openApi.getComponents().getSecuritySchemes();
        require(schemes != null, "OpenAPI 缺少 securitySchemes");
        SecurityScheme user = schemes.get("UserBearerAuth");
        require(user != null && user.getType() == SecurityScheme.Type.HTTP
                        && "bearer".equalsIgnoreCase(user.getScheme()), "UserBearerAuth 非法");
        SecurityScheme basic = schemes.get("OAuthClientBasic");
        require(basic != null && basic.getType() == SecurityScheme.Type.HTTP
                        && "basic".equalsIgnoreCase(basic.getScheme()), "OAuthClientBasic 非法");
        SecurityScheme platformRefresh = schemes.get("PlatformRefreshCookieAuth");
        require(platformRefresh != null && platformRefresh.getType() == SecurityScheme.Type.APIKEY
                        && platformRefresh.getIn() == SecurityScheme.In.COOKIE
                        && "__Host-sf_platform_refresh".equals(platformRefresh.getName()),
                "PlatformRefreshCookieAuth 非法");
        SecurityScheme tenantRefresh = schemes.get("TenantRefreshCookieAuth");
        require(tenantRefresh != null && tenantRefresh.getType() == SecurityScheme.Type.APIKEY
                        && tenantRefresh.getIn() == SecurityScheme.In.COOKIE
                        && "__Host-sf_tenant_refresh".equals(tenantRefresh.getName()),
                "TenantRefreshCookieAuth 非法");
        SecurityScheme service = schemes.get("ServiceOAuth2");
        OAuthFlow clientCredentials = service == null || service.getFlows() == null
                ? null : service.getFlows().getClientCredentials();
        Set<String> publicScopes = new HashSet<>();
        scopes.values().stream().filter(ScopeEntry::gatewayRouteAllowed)
                .map(ScopeEntry::scope).forEach(publicScopes::add);
        require(service != null && service.getType() == SecurityScheme.Type.OAUTH2
                        && clientCredentials != null
                        && "/oauth2/token".equals(clientCredentials.getTokenUrl())
                        && clientCredentials.getScopes() != null
                        && publicScopes.equals(clientCredentials.getScopes().keySet()),
                "ServiceOAuth2 必须精确声明可用于公网的固定 Scope");
    }

    private static void validateConsoleSecuritySchemes(OpenAPI openApi) {
        var schemes = openApi.getComponents().getSecuritySchemes();
        for (var entry : Map.of("ConsoleSlotCookieAuth", "__Host-sf_console_slot",
                "ConsoleRefreshCookieAuth", "__Host-sf_console_refresh").entrySet()) {
            var scheme = schemes.get(entry.getKey());
            require(scheme != null && scheme.getType() == SecurityScheme.Type.APIKEY
                    && scheme.getIn() == SecurityScheme.In.COOKIE && entry.getValue().equals(scheme.getName()),
                    entry.getKey() + " 非法");
        }
    }

    private static List<Route> generateRoutes(
            OpenAPI openApi, Map<String, ServiceEntry> services, Map<String, ScopeEntry> scopes) {
        require(openApi.getPaths() != null && !openApi.getPaths().isEmpty(), "OpenAPI paths 不能为空");
        List<Route> routes = new ArrayList<>();
        Set<String> operationIds = new HashSet<>();
        Set<String> normalizedMethodPaths = new HashSet<>();
        openApi.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
            String operationId = operation.getOperationId();
            Object ownerExtension = operation.getExtensions() == null
                    ? null : operation.getExtensions().get("x-saas.forge-service");
            String serviceId = ownerExtension instanceof String value ? value : null;
            require(operationId != null && operationId.matches("[A-Za-z][A-Za-z0-9]*"),
                    method + " " + path + " 缺少合法 operationId");
            ServiceEntry service = services.get(serviceId);
            require(service != null && service.gatewayRouteTargetAllowed(),
                    operationId + " 使用了未登记或无公网资格的 serviceId: " + serviceId);
            require(operationIds.add(operationId), "operationId 重复: " + operationId);
            String normalizedPath = PATH_VARIABLE.matcher(path).replaceAll("{}");
            require(normalizedMethodPaths.add(method + " " + normalizedPath),
                    "OpenAPI method/path 规范化冲突: " + method + " " + normalizedPath);
            Credential credential = credential(operation, openApi, scopes);
            routes.add(new Route(operationId, method.name(), path, serviceId,
                    credential.requirement(), credential.requiredScopes()));
        }));
        routes.sort(Comparator.comparing(Route::method).thenComparing(Route::path).thenComparing(Route::operationId));
        return List.copyOf(routes);
    }

    private static Credential credential(
            Operation operation, OpenAPI openApi, Map<String, ScopeEntry> scopes) {
        List<SecurityRequirement> security = operation.getSecurity() != null
                ? operation.getSecurity() : openApi.getSecurity();
        if (security == null || security.isEmpty()) {
            return new Credential("ANONYMOUS", List.of());
        }
        if (security.size() == 1 && security.get(0).isEmpty()) {
            return new Credential("ANONYMOUS", List.of());
        }
        if (security.size() == 2 && security.stream().anyMatch(Map::isEmpty)) {
            SecurityRequirement required = security.stream().filter(item -> !item.isEmpty()).findFirst().orElseThrow();
            require(required.size() == 1 && required.containsKey("UserBearerAuth")
                            && required.get("UserBearerAuth").isEmpty(),
                    operation.getOperationId() + " 只有 UserBearerAuth 可与 anonymous 组成 optional");
            return new Credential("USER_OPTIONAL", List.of());
        }
        if (security.size() == 2
                && security.stream().allMatch(requirement -> requirement.size() == 1)
                && security.stream().flatMap(requirement -> requirement.keySet().stream()).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of("PlatformRefreshCookieAuth", "TenantRefreshCookieAuth"))) {
            require(security.stream().flatMap(requirement -> requirement.values().stream())
                            .allMatch(value -> value == null || value.isEmpty()),
                    operation.getOperationId() + " 的 Browser Session Slot 不允许 Scope");
            return new Credential("BROWSER_SESSION_SLOT_REQUIRED", List.of());
        }
        if (security.size() == 1 && security.get(0).keySet()
                .equals(Set.of("ConsoleSlotCookieAuth", "ConsoleRefreshCookieAuth"))) {
            require(security.get(0).values().stream().allMatch(List::isEmpty),
                    operation.getOperationId() + " 的 Console Cookie 不允许 Scope");
            return new Credential("CONSOLE_SESSION_REQUIRED", List.of());
        }
        require(security.size() == 1 && security.get(0).size() == 1,
                operation.getOperationId() + " security alternatives 必须互斥且唯一");
        Map.Entry<String, List<String>> requirement = security.get(0).entrySet().iterator().next();
        List<String> requiredScopes = requirement.getValue() == null ? List.of() : requirement.getValue();
        return switch (requirement.getKey()) {
            case "ConsoleSlotCookieAuth" -> noScopes(operation, requiredScopes, "CONSOLE_SLOT_REQUIRED");
            case "UserBearerAuth" -> noScopes(operation, requiredScopes, "USER_REQUIRED");
            case "PlatformRefreshCookieAuth" -> noScopes(
                    operation, requiredScopes, "PLATFORM_REFRESH_COOKIE_REQUIRED");
            case "TenantRefreshCookieAuth" -> noScopes(
                    operation, requiredScopes, "TENANT_REFRESH_COOKIE_REQUIRED");
            case "OAuthClientBasic" -> noScopes(operation, requiredScopes, "OAUTH_CLIENT_BASIC_REQUIRED");
            case "ServiceOAuth2" -> serviceCredential(operation, requiredScopes, scopes);
            default -> throw new IllegalArgumentException(
                    operation.getOperationId() + " 使用未知 Security Scheme: " + requirement.getKey());
        };
    }

    private static Credential noScopes(Operation operation, List<String> scopes, String requirement) {
        require(scopes.isEmpty(), operation.getOperationId() + " 的 " + requirement + " 不允许 Scope");
        return new Credential(requirement, List.of());
    }

    private static Credential serviceCredential(
            Operation operation, List<String> requiredScopes, Map<String, ScopeEntry> scopes) {
        require(!requiredScopes.isEmpty()
                        && new LinkedHashSet<>(requiredScopes).size() == requiredScopes.size(),
                operation.getOperationId() + " 的 ServiceOAuth2 Scope 不能为空或重复");
        for (String scope : requiredScopes) {
            ScopeEntry entry = scopes.get(scope);
            require(entry != null && entry.gatewayRouteAllowed(),
                    operation.getOperationId() + " 使用未登记或无公网资格的 Scope: " + scope);
        }
        return new Credential("SERVICE_REQUIRED", requiredScopes.stream().sorted().toList());
    }

    private static void validateRouteEligibility(
            List<Route> routes, Map<String, ServiceEntry> services) {
        Set<String> routedServices = routes.stream().map(Route::serviceId).collect(java.util.stream.Collectors.toSet());
        for (ServiceEntry service : services.values()) {
            require(!service.gatewayRouteTargetAllowed() || routedServices.contains(service.serviceId()),
                    service.serviceId() + " 允许 Gateway 路由但没有正式 OpenAPI operation");
            require(service.gatewayRouteTargetAllowed() || !routedServices.contains(service.serviceId()),
                    service.serviceId() + " 没有公网资格却产生了 Route Catalog 条目");
        }
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record ServiceRegistry(int registryVersion, List<ServiceEntry> entries) {
    }

    private record ServiceEntry(
            String serviceId,
            String owner,
            String modulePath,
            String artifactId,
            String nacosServiceName,
            boolean deployable,
            boolean gatewayRouteTargetAllowed) {
    }

    private record ScopeRegistry(int registryVersion, List<ScopeEntry> entries) {
    }

    private record ScopeEntry(
            String scope,
            String ownerServiceId,
            List<String> clientTypes,
            String usage,
            boolean gatewayRouteAllowed,
            String description) {
    }

    private record Catalog(int schemaVersion, List<Route> routes) {
    }

    private record Route(
            String operationId,
            String method,
            String path,
            String serviceId,
            String credentialRequirement,
            List<String> requiredScopes) {
    }

    private record Credential(String requirement, List<String> requiredScopes) {
    }
}
