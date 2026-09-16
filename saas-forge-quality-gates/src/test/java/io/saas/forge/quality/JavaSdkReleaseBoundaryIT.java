package io.saas.forge.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class JavaSdkReleaseBoundaryIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = Path.of(System.getProperty("repositoryRoot"));
    private static final Path ALLOWLIST = REPOSITORY.resolve("saas-forge-sdk/public-api-allowlist.json");
    private static final Path EXTERNAL_CONSUMER = REPOSITORY.resolve("test-support/saas-forge-external-consumer-fixture/pom.xml");
    private static final Set<String> CONSUMER_ARTIFACTS = Set.of(
            "saas-forge-sdk-core",
            "saas-forge-sdk-auth",
            "saas-forge-sdk-tenant",
            "saas-forge-spring-boot-starter");
    private static final Set<String> PUBLISHED_ARTIFACTS = Set.of(
            "saas-forge",
            "saas-forge-http-route-catalog",
            "saas-forge-bom",
            "saas-forge-sdk-core",
            "saas-forge-sdk-auth",
            "saas-forge-sdk-tenant",
            "saas-forge-spring-boot-starter");
    private static final Set<String> STARTER_SDK_DEPENDENCIES = Set.of(
            "saas-forge-sdk-core",
            "saas-forge-sdk-auth",
            "saas-forge-sdk-tenant");
    private static final List<String> FORBIDDEN_TYPE_REFERENCES = List.of(
            "com.google.protobuf.",
            "io.grpc.",
            "org.apache.ibatis.",
            "org.mybatis.",
            "org.flywaydb.",
            ".persistence.",
            ".repository.");
    private static final Pattern FORBIDDEN_BROWSER_API_TERM = Pattern.compile(
            "(^|[^a-z])(cookie|origin)([^a-z]|$)|sec[-_]?fetch|fetch[-_]?metadata",
            Pattern.CASE_INSENSITIVE);

    @Test
    void publicationWhitelistMatchesEveryRepositoryPom() throws Exception {
        Map<String, Boolean> deploymentByArtifact = new LinkedHashMap<>();
        for (Path pom : repositoryPoms()) {
            Document document = parseXml(pom);
            String artifactId = directChildText(document.getDocumentElement(), "artifactId");
            String deploySkip = property(document, "maven.deploy.skip");
            assertNotNull(deploySkip, pom + " 必须显式声明 maven.deploy.skip 以加入发布白名单或拒绝发布");
            assertTrue(Set.of("true", "false").contains(deploySkip),
                    pom + " 的 maven.deploy.skip 只能是 true 或 false");
            assertTrue(deploymentByArtifact.put(artifactId, Boolean.valueOf(deploySkip)) == null,
                    "Maven artifactId 重复，无法形成精确发布白名单: " + artifactId);
        }

        Set<String> publishable = new LinkedHashSet<>();
        deploymentByArtifact.forEach((artifact, skipped) -> {
            if (!skipped) {
                publishable.add(artifact);
            }
        });
        assertEquals(PUBLISHED_ARTIFACTS, publishable,
                "Maven Central 只能发布父 POM、Route Catalog 支撑制品和首版消费者制品");
    }

    @Test
    void bomAndStarterExposeOnlyTheSupportedFirstRelease() throws Exception {
        Document bom = parseXml(REPOSITORY.resolve("saas-forge-sdk/saas-forge-java/saas-forge-bom/pom.xml"));
        Map<String, String> managed = dependencies(bom);
        assertEquals(CONSUMER_ARTIFACTS, managed.keySet(), "BOM 只能管理首版四个消费者制品");
        managed.forEach((artifact, version) -> assertEquals("${project.version}", version,
                "BOM 必须以同一 project.version 管理 " + artifact));

        Document starter = parseXml(REPOSITORY.resolve(
                "saas-forge-sdk/saas-forge-starters/saas-forge-spring-boot-starter/pom.xml"));
        Set<String> starterSdkDependencies = new LinkedHashSet<>();
        for (String artifact : dependencies(starter).keySet()) {
            if (artifact.startsWith("saas-forge-sdk-")) {
                starterSdkDependencies.add(artifact);
            }
        }
        assertEquals(STARTER_SDK_DEPENDENCIES, starterSdkDependencies,
                "Starter 在公开 SDK 中只能传递 core、auth 与 tenant");

        Set<String> unpublishedInternalDependencies = new LinkedHashSet<>();
        dependencies(starter).keySet().stream()
                .filter(artifact -> artifact.startsWith("saas-forge-"))
                .filter(artifact -> !PUBLISHED_ARTIFACTS.contains(artifact))
                .forEach(unpublishedInternalDependencies::add);
        assertTrue(unpublishedInternalDependencies.isEmpty(),
                "Starter 消费者 POM 不得引用未发布的仓库制品: " + unpublishedInternalDependencies);
    }

    @Test
    void externalConsumerUsesOnlyTheBomAndStarterPublicEntryPoint() throws Exception {
        Document consumer = parseXml(EXTERNAL_CONSUMER);
        Node project = consumer.getDocumentElement();
        Node parent = directChild(project, "parent");
        assertNotNull(parent, "外部消费者必须显式选择自己的 Spring Boot parent");
        assertEquals("org.springframework.boot", directChildText(parent, "groupId"));
        assertEquals("spring-boot-starter-parent", directChildText(parent, "artifactId"));
        assertEquals("", directChildText(parent, "relativePath"),
                "外部消费者不得通过 relativePath 继承 saas-forge 根 POM");

        Document root = parseXml(REPOSITORY.resolve("pom.xml"));
        assertEquals(
                directChildText(directChild(root.getDocumentElement(), "parent"), "version"),
                directChildText(parent, "version"),
                "消费者 Spring Boot 基线必须与当前仓库一致");
        assertEquals(
                property(root, "revision"),
                property(consumer, "saas-forge.version"),
                "消费者必须以当前 Reactor 版本导入 BOM");

        List<Node> managed = dependencyNodes(directChild(project, "dependencyManagement"));
        assertEquals(1, managed.size(), "消费者 dependencyManagement 只能导入 saas-forge BOM");
        Node bom = managed.get(0);
        assertEquals("io.github.crane199709", directChildText(bom, "groupId"));
        assertEquals("saas-forge-bom", directChildText(bom, "artifactId"));
        assertEquals("${saas-forge.version}", directChildText(bom, "version"));
        assertEquals("pom", directChildText(bom, "type"));
        assertEquals("import", directChildText(bom, "scope"));

        List<Node> saasForgeDependencies = dependencyNodes(project).stream()
                .filter(dependency -> "io.github.crane199709".equals(directChildText(dependency, "groupId")))
                .toList();
        assertEquals(1, saasForgeDependencies.size(), "消费者只能直接声明一个 saas-forge 依赖");
        Node starter = saasForgeDependencies.get(0);
        assertEquals("saas-forge-spring-boot-starter", directChildText(starter, "artifactId"));
        assertTrue(directChildText(starter, "version") == null, "Starter 版本必须由 BOM 解析");

        Node acceptanceProfile = profile(root, "sdk-external-consumer-acceptance");
        assertNotNull(acceptanceProfile, "根项目必须提供外部消费者验收 profile");
        assertEquals(
                "reactorModuleConvergence",
                directChildText(directChild(acceptanceProfile, "properties"), "enforcer.skipRules"),
                "验收 profile 只能按设计跳过父 POM 收敛规则");
        assertEquals(
                List.of("test-support/saas-forge-external-consumer-fixture"),
                directChildTexts(directChild(acceptanceProfile, "modules"), "module"),
                "验收 profile 必须只引入独立消费者夹具");
    }

    @Test
    void compiledPublicSurfaceMatchesTheExplicitAllowlist() throws Exception {
        JsonNode artifacts = JSON.readTree(ALLOWLIST.toFile()).path("artifacts");
        assertEquals(CONSUMER_ARTIFACTS, fieldNames(artifacts),
                "公共 API allowlist 必须覆盖且只能覆盖首版四个消费者制品");

        for (String artifact : CONSUMER_ARTIFACTS) {
            JsonNode boundary = artifacts.path(artifact);
            Set<String> allowedPackages = textSet(boundary.path("packages"));
            Set<String> allowedTypes = textSet(boundary.path("publicTypes"));
            assertFalse(allowedPackages.isEmpty(), artifact + " 必须声明公共 package allowlist");
            assertFalse(allowedTypes.isEmpty(), artifact + " 必须显式列出公共类型");

            Path jar = currentJar(boundary.path("module").asText(), artifact);
            Set<String> actualPublicTypes = publicTypes(jar);
            assertEquals(allowedTypes, actualPublicTypes,
                    artifact + " 的公共类型变化必须先经过 allowlist 评审");
            Set<String> actualPublicPackages = new LinkedHashSet<>();
            for (String type : actualPublicTypes) {
                Class<?> publicType = Class.forName(type, false, Thread.currentThread().getContextClassLoader());
                actualPublicPackages.add(publicType.getPackageName());
                assertSafePublicSignatures(artifact, publicType);
            }
            assertEquals(allowedPackages, actualPublicPackages,
                    artifact + " 的公共 package 变化必须先经过 allowlist 评审");
            assertSafeJarContents(artifact, jar);
            assertSafeImplementationReferences(artifact, jar);
        }
    }

    private static void assertSafePublicSignatures(String artifact, Class<?> publicType) {
        List<String> signatures = new ArrayList<>();
        signatures.add(publicType.getTypeName());
        for (Field field : publicType.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                signatures.add(field.toGenericString());
            }
        }
        for (Method method : publicType.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                signatures.add(method.toGenericString());
                signatures.addAll(parameterNames(method));
            }
        }
        Stream.of(publicType.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .forEach(constructor -> {
                    signatures.add(constructor.toGenericString());
                    signatures.addAll(parameterNames(constructor));
                });

        for (String signature : signatures) {
            String normalized = signature.toLowerCase();
            FORBIDDEN_TYPE_REFERENCES.forEach(forbidden -> assertFalse(
                    normalized.contains(forbidden.toLowerCase()),
                    artifact + " 公共签名泄漏内部类型: " + signature));
            assertFalse(FORBIDDEN_BROWSER_API_TERM.matcher(normalized).find(),
                    artifact + " 公共 API 暴露浏览器管理的安全参数: " + signature);
        }
    }

    private static List<String> parameterNames(Executable executable) {
        return Stream.of(executable.getParameters()).map(parameter -> parameter.getName()).toList();
    }

    private static void assertSafeJarContents(String artifact, Path jar) throws IOException {
        try (JarFile archive = new JarFile(jar.toFile())) {
            archive.stream().map(entry -> entry.getName().toLowerCase()).forEach(entry -> {
                assertFalse(entry.contains("/db/migration/")
                                || entry.contains("/persistence/")
                                || entry.contains("/repository/")
                                || entry.endsWith("mapper.class")
                                || entry.endsWith("persistenceentity.class")
                                || entry.endsWith("databaseentity.class"),
                        artifact + " JAR 包含持久化或迁移实现: " + entry);
            });
        }
    }

    private static void assertSafeImplementationReferences(String artifact, Path jar) throws Exception {
        Path jdeps = Path.of(System.getProperty("java.home"), "bin", "jdeps");
        Process process = new ProcessBuilder(
                jdeps.toString(), "--ignore-missing-deps", "--multi-release", "base", "-verbose:class", jar.toString())
                .redirectErrorStream(true)
                .start();
        CompletableFuture<String> outputRead = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalStateException("无法读取 jdeps 输出", exception);
            }
        });
        boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, artifact + " 的 jdeps 检查超时");
        String output = outputRead.join();
        assertEquals(0, process.exitValue(), artifact + " 的 jdeps 检查失败:\n" + output);
        String normalized = output.toLowerCase();
        FORBIDDEN_TYPE_REFERENCES.forEach(forbidden -> assertFalse(
                normalized.contains(forbidden.toLowerCase()),
                artifact + " 实现引用内部协议或持久化类型 " + forbidden + ":\n" + output));
    }

    private static Set<String> publicTypes(Path jar) throws Exception {
        Set<String> publicTypes = new LinkedHashSet<>();
        try (JarFile archive = new JarFile(jar.toFile())) {
            for (String type : archive.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.equals("module-info.class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .sorted()
                    .toList()) {
                Class<?> candidate = Class.forName(type, false, Thread.currentThread().getContextClassLoader());
                if (isExternallyPublic(candidate)) {
                    publicTypes.add(type);
                }
            }
        }
        return publicTypes;
    }

    private static boolean isExternallyPublic(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (!Modifier.isPublic(current.getModifiers())) {
                return false;
            }
        }
        return true;
    }

    private static Path currentJar(String module, String artifact) throws IOException {
        Path target = REPOSITORY.resolve(module).resolve("target");
        try (Stream<Path> files = Files.list(target)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(artifact + "-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-javadoc.jar"))
                    .max(Comparator.comparingLong(JavaSdkReleaseBoundaryIT::lastModified))
                    .orElseThrow(() -> new AssertionError("缺少待检查 JAR: " + artifact));
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 JAR 修改时间: " + path, exception);
        }
    }

    private static List<Path> repositoryPoms() throws IOException {
        try (Stream<Path> paths = Files.walk(REPOSITORY)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .sorted()
                    .toList();
        }
    }

    private static Map<String, String> dependencies(Document document) {
        Map<String, String> dependencies = new LinkedHashMap<>();
        NodeList nodes = document.getElementsByTagName("dependency");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node dependency = nodes.item(index);
            String groupId = directChildText(dependency, "groupId");
            if (!Set.of("io.github.crane199709", "${project.groupId}").contains(groupId)) {
                continue;
            }
            String artifactId = directChildText(dependency, "artifactId");
            String version = directChildText(dependency, "version");
            assertTrue(dependencies.put(artifactId, version) == null, "依赖重复: " + artifactId);
        }
        return dependencies;
    }

    private static List<Node> dependencyNodes(Node section) {
        Node dependencies = section == null ? null : directChild(section, "dependencies");
        if (dependencies == null) {
            return List.of();
        }
        List<Node> nodes = new ArrayList<>();
        for (Node child = dependencies.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && "dependency".equals(child.getNodeName())) {
                nodes.add(child);
            }
        }
        return List.copyOf(nodes);
    }

    private static Node profile(Document document, String id) {
        Node profiles = directChild(document.getDocumentElement(), "profiles");
        if (profiles == null) {
            return null;
        }
        for (Node child = profiles.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && "profile".equals(child.getNodeName())
                    && id.equals(directChildText(child, "id"))) {
                return child;
            }
        }
        return null;
    }

    private static List<String> directChildTexts(Node parent, String name) {
        List<String> values = new ArrayList<>();
        if (parent == null) {
            return values;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                values.add(child.getTextContent().trim());
            }
        }
        return List.copyOf(values);
    }

    private static String property(Document document, String name) {
        Node properties = directChild(document.getDocumentElement(), "properties");
        if (properties == null) {
            return null;
        }
        return directChildText(properties, name);
    }

    private static String directChildText(Node parent, String name) {
        Node child = directChild(parent, name);
        return child == null ? null : child.getTextContent().trim();
    }

    private static Node directChild(Node parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return child;
            }
        }
        return null;
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

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        assertTrue(array.isArray(), "allowlist 字段必须是数组: " + array);
        array.forEach(value -> {
            assertTrue(value.isTextual() && !value.asText().isBlank(), "allowlist 值必须是非空字符串: " + value);
            assertTrue(values.add(value.asText()), "allowlist 值重复: " + value.asText());
        });
        return values;
    }
}
