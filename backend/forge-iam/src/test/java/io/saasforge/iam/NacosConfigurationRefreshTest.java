package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.saasforge.sdk.tenant.TenantAutoConfiguration;
import io.saasforge.sdk.tenant.TenantSessionDataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;

class NacosConfigurationRefreshTest {
    @Test
    void sharedConfigurationKeepsTenantDataSourceOutOfRebinding() throws IOException {
        // 读取实际发布到 Nacos 的公共配置，避免测试副本掩盖部署配置遗漏。
        var resource = new FileSystemResource(
                Path.of(System.getProperty("basedir"), "../../deploy/nacos/config/forge-common.yaml"));
        var sources = new YamlPropertySourceLoader().load("forge-common", resource);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TenantAutoConfiguration.class, ConfigurationPropertiesRebinderAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfiguration.class)
                .withInitializer(context -> sources.forEach(
                        source -> context.getEnvironment().getPropertySources().addLast(source)))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:postgresql://localhost/initial",
                        "spring.datasource.hikari.maximum-pool-size=4")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DataSource dataSource = context.getBean(DataSource.class);
                    assertThat(dataSource).isInstanceOf(TenantSessionDataSource.class);
                    HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
                    var properties = context.getBean(DataSourceProperties.class);

                    Map<String, Object> changes = Map.of(
                            "spring.datasource.url", "jdbc:postgresql://localhost/updated",
                            "spring.datasource.hikari.maximum-pool-size", "9");
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("changed", changes));
                    context.publishEvent(new EnvironmentChangeEvent(changes.keySet()));

                    assertThat(context.getBean(DataSource.class)).isSameAs(dataSource);
                    assertThat(pool.isClosed()).isFalse();
                    assertThat(pool.getMaximumPoolSize()).isEqualTo(4);
                    assertThat(properties.getUrl()).isEqualTo("jdbc:postgresql://localhost/updated");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class DataSourceConfiguration {
        @Bean
        @ConfigurationProperties("spring.datasource.hikari")
        HikariDataSource dataSource() {
            return new HikariDataSource();
        }
    }
}
