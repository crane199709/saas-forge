package io.saasforge.sdk.manifest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** 服务启动完成后生成 Resource Manifest 并上报 iam；{@code forge.manifest.enabled=false} 时不上报。 */
@AutoConfiguration
@EnableConfigurationProperties(ForgeManifestProperties.class)
public final class ManifestAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ManifestAutoConfiguration.class);

    /**
     * 扫描应用根包（{@code @SpringBootApplication} 所在包）中的 {@code @RequiresPermission}。
     *
     * <p>清单错误（如菜单引用未声明的权限码）在生成阶段即启动失败；上报失败只记录错误、不阻止启动：
     * iam 暂时不可用不应拖垮业务服务，下次启动或 CI 上报会补齐。
     *
     * @param properties 上报配置
     * @param environment 读取 {@code spring.application.name}
     * @param beanFactory 读取应用根包
     * @return 启动完成监听器
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> forgeManifestReporter(
            ForgeManifestProperties properties, Environment environment, BeanFactory beanFactory) {
        return event -> {
            if (!properties.enabled()) return;
            String service = environment.getRequiredProperty("spring.application.name");
            var manifest = ResourceManifestCollector.collect(
                    service,
                    properties.version(),
                    AutoConfigurationPackages.get(beanFactory).get(0),
                    event.getApplicationContext().getClassLoader());
            try {
                new ManifestReporter(properties.iamUri(), properties.token()).report(manifest);
                log.info("Reported resource manifest {}@{}", service, properties.version());
            } catch (RuntimeException error) {
                log.error("Failed to report resource manifest {}@{}", service, properties.version(), error);
            }
        };
    }
}
