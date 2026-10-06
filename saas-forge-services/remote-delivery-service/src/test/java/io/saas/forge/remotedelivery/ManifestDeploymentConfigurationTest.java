package io.saas.forge.remotedelivery;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class ManifestDeploymentConfigurationTest {
    @Test
    void composeEnvironmentBindsOnlyTheApprovedModuleClient() throws Exception {
        var root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("deploy/acceptance/stage3.override.yaml"))) {
            root = root.getParent();
            assertNotNull(root,"Repository deployment configuration must exist");
        }
        var compose = Files.readString(root.resolve("deploy/acceptance/stage3.override.yaml"));
        var matcher = java.util.regex.Pattern.compile("(?m)^\\s+(SAAS_FORGE_[A-Z_]+): \\$\\{PROJECT_MANIFEST_CLIENT_ID:").matcher(compose);
        assertTrue(matcher.find());
        var client = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of(matcher.group(1),client.toString(),"BROWSER_ROOTDOMAIN","saas.forge.test")));
        var policy = new RemoteDeliveryConfiguration().manifestPolicy(environment);
        var declaration = new RegisterManifestRequest("project","1.0.0",
                "https://remote.saas.forge.test/project/1.0.0/remote.js","1.0.0","a".repeat(64));
        assertDoesNotThrow(() -> policy.requireRegistration(client,declaration));
        assertEquals("CI_MODULE_NOT_ALLOWED",assertThrows(ManifestException.class,
                () -> policy.requireRegistration(UUID.randomUUID(),declaration)).code());
    }
}
