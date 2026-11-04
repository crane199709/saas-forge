package io.saasforge.sdk.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class BusinessZonePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Enabled.class);

    @Test
    void bindsConfiguredBusinessZone() {
        runner.withPropertyValues("forge.business-zone=Asia/Shanghai")
                .run(context -> assertThat(
                                context.getBean(BusinessZoneProperties.class).require("Business Number"))
                        .isEqualTo(ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void missingBusinessZoneFailsOnlyWhenRequired() {
        runner.run(context -> {
            var properties = context.getBean(BusinessZoneProperties.class);
            assertThat(properties.businessZone()).isNull();
            assertThatThrownBy(() -> properties.require("Business Number"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("forge.business-zone")
                    .hasMessageContaining("Business Number");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BusinessZoneProperties.class)
    static class Enabled {}
}
