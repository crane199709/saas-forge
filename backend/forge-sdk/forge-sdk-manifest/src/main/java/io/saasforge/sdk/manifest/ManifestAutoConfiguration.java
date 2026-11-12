package io.saasforge.sdk.manifest;

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
    /** 创建可随应用关闭的有限上报任务。 */
    @Bean(destroyMethod = "close")
    public ManifestReporting manifestReporting(ForgeManifestProperties properties) {
        return new ManifestReporting(
                properties.enabled(),
                properties.enabled() ? new ManifestReporter(properties.iamUri(), properties.token()) : null);
    }
    /** 状态端点由 Ops 独立安全链保护。 */
    @Bean
    public OnboardingStatusController onboardingStatusController(ManifestReporting reporting) {
        return new OnboardingStatusController(reporting);
    }

    /**
     * 扫描应用根包（{@code @SpringBootApplication} 所在包）中的 {@code @RequiresPermission}。
     *
     * <p>清单错误（如菜单引用未声明的权限码）在生成阶段即启动失败；上报失败只记录错误、不阻止启动：
     * iam 暂时不可用不应拖垮业务服务，后台有限补报。
     *
     * @param properties 上报配置
     * @param environment 读取 {@code spring.application.name}
     * @param beanFactory 读取应用根包
     * @return 启动完成监听器
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> forgeManifestReporter(
            ForgeManifestProperties properties,
            Environment environment,
            BeanFactory beanFactory,
            ManifestReporting reporting) {
        return event -> {
            if (!properties.enabled()) return;
            String service = environment.getRequiredProperty("spring.application.name");
            var manifest = ResourceManifestCollector.collect(
                    service,
                    properties.version(),
                    AutoConfigurationPackages.get(beanFactory).get(0),
                    event.getApplicationContext().getClassLoader());
            reporting.start(manifest);
        };
    }
}
