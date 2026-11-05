package io.saasforge.iam.mfa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class MfaPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void missingEncryptionKeyFailsStartup() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("forge.iam.mfa.encryption-key"));
    }

    @Test
    void keyThatIsNotBase64EncodedAes256FailsStartup() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        for (String value : new String[] {shortKey, "not base64!"}) {
            runner.withPropertyValues("forge.iam.mfa.encryption-key=" + value)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining("forge.iam.mfa.encryption-key"));
        }
    }

    @Test
    void base64EncodedAes256KeyBinds() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        runner.withPropertyValues("forge.iam.mfa.encryption-key=" + key).run(context -> {
            var properties = context.getBean(MfaProperties.class);
            assertThat(properties.key()).hasSize(32);
            assertThat(properties.toString()).doesNotContain(key);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MfaProperties.class)
    static class Binding {}
}
