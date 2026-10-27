package io.saasforge.sdk.web;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 可观测性的 SDK 默认配置，优先级最低，服务与 Nacos 中的同名配置都会覆盖它。
 *
 * <ul>
 *   <li>日志关联信息在 traceId、spanId 之后追加 SDK 写入 MDC 的租户与 Membership；没有租户的日志（启动、健康检查）不输出这一段。
 *   <li>OTLP 指标推送默认关闭：Boot 默认每分钟推往 {@code localhost:4318}，未部署采集端时只会反复报错。部署时以
 *       {@code management.otlp.metrics.export.enabled=true} 与 {@code url} 开启。trace 的 OTLP 导出在配置
 *       {@code management.opentelemetry.tracing.export.otlp.endpoint} 后才生效，无需在这里关闭。
 * </ul>
 */
public final class ObservabilityDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "forgeObservabilityDefaults";

    // %correlationId 即 Boot 默认的 [traceId-spanId]；替换掉租户与 Membership 都为空时剩下的 "[,] "
    static final String CORRELATION_PATTERN =
            "%correlationId%replace([%X{tenant_id},%X{membership_id}] ){'^.,. $', ''}";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment
                .getPropertySources()
                .addLast(new MapPropertySource(
                        SOURCE_NAME,
                        Map.of(
                                "logging.pattern.correlation",
                                CORRELATION_PATTERN,
                                "management.otlp.metrics.export.enabled",
                                "false")));
    }
}
