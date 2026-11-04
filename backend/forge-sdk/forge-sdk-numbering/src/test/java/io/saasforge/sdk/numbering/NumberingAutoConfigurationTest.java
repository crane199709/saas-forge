package io.saasforge.sdk.numbering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NumberingAutoConfigurationTest {
    @Test
    void serviceUsingBusinessNumbersFailsToStartWithoutBusinessZone() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NumberingAutoConfiguration.class))
                .withBean(
                        SqlSessionTemplate.class,
                        () -> Mockito.mock(SqlSessionTemplate.class, Mockito.RETURNS_DEEP_STUBS))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("forge.business-zone"));
    }
}
