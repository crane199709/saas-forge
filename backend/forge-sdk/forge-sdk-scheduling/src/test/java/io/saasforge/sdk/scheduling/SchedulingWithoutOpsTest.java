package io.saasforge.sdk.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SchedulingWithoutOpsTest {
    static final SharedContainers.Redis REDIS = SharedContainers.redis();

    @Test
    void aServiceWithoutTheOpsModuleHasNoOpsRouteAndStillSchedulesTasks() {
        var configuration = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        configuration.setDatabase(REDIS.database());
        var factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        var redis = new StringRedisTemplate(factory);
        var scheduler = new ManualScheduler();
        try {
            new WebApplicationContextRunner()
                    .withClassLoader(new FilteredClassLoader("io.saasforge.sdk.ops"))
                    .withConfiguration(
                            AutoConfigurations.of(SchedulingAutoConfiguration.class, WebMvcAutoConfiguration.class))
                    .withBean(StringRedisTemplate.class, () -> redis)
                    .withBean("forgeTaskScheduler", TaskScheduler.class, () -> scheduler)
                    .withBean(Probe.class)
                    .withPropertyValues(
                            "spring.application.name=without-ops-test",
                            "spring.data.redis.database=" + REDIS.database())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        int status = MockMvcBuilders.webAppContextSetup(context)
                                .build()
                                .perform(get("/internal/ops/tasks"))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                        assertThat(status).isEqualTo(404);
                        scheduler.trigger.run();
                        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                            var runs = context.getBean(TaskRunHistory.class).recent("probe");
                            assertThat(runs)
                                    .singleElement()
                                    .satisfies(run -> assertThat(run.status()).isEqualTo(TaskRun.Status.SUCCEEDED));
                        });
                    });
        } finally {
            factory.destroy();
        }
    }

    static class Probe {
        @GlobalTask(name = "probe", fixedDelay = "1h")
        void execute() {}
    }

    static class ManualScheduler extends ThreadPoolTaskScheduler {
        Runnable trigger;

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            trigger = task;
            return null;
        }
    }
}
