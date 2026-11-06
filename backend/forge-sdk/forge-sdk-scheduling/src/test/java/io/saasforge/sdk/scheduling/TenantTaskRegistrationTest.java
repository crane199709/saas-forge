package io.saasforge.sdk.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.sdk.tenant.TenantStatusLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.SimpleTriggerContext;

class TenantTaskRegistrationTest {
    @Test
    void makesBoundDirectoryConfigurationAvailableToSpringBeans() {
        var scheduler = new GlobalTaskRegistrationTest.RecordingScheduler();
        configured(scheduler).withBean(ConfiguredTask.class).run(context -> {
            assertThat(context).hasNotFailed();
            SchedulingProperties directory = context.getBean(ConfiguredTask.class).directory;
            assertThat(directory.iamUri()).isEqualTo("http://localhost");
            assertThat(directory.token()).isEqualTo("service-token");
            assertThat(directory.toString()).doesNotContain("service-token");
            assertThat(scheduler.delay).isEqualTo(Duration.ofSeconds(1));
        });
    }

    static class ConfiguredTask {
        final SchedulingProperties directory;

        ConfiguredTask(SchedulingProperties directory) {
            this.directory = directory;
        }

        @TenantTask(name = "configured", fixedDelay = "1s")
        void execute() {}
    }

    @Test
    void registersFixedDelayWithoutRequiringBusinessZone() {
        var scheduler = new GlobalTaskRegistrationTest.RecordingScheduler();
        configured(scheduler)
                .withBean(FixedTask.class)
                .withPropertyValues("task.delay=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(scheduler.delay).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Test
    void cronUsesBusinessZoneAndRequiresItAtStartup() {
        var scheduler = new GlobalTaskRegistrationTest.RecordingScheduler();
        configured(scheduler)
                .withBean(CronTask.class)
                .withPropertyValues("forge.business-zone=Asia/Shanghai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var triggerContext = new SimpleTriggerContext(
                            Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC));
                    assertThat(scheduler.trigger.nextExecution(triggerContext))
                            .isEqualTo(Instant.parse("2026-11-06T01:00:00Z"));
                });
        configured(new GlobalTaskRegistrationTest.RecordingScheduler())
                .withBean(CronTask.class)
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasMessageContaining("forge.business-zone"));
    }

    @ParameterizedTest
    @MethodSource("missingDirectoryConfiguration")
    void requiresDirectoryConfigurationOnlyWhenTenantTasksAreDeclared(
            String configuredProperty, String missingProperty) {
        runner(new GlobalTaskRegistrationTest.RecordingScheduler())
                .withBean(FixedTask.class)
                .withPropertyValues("task.delay=1s", configuredProperty)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining(missingProperty + " must be set"));
    }

    static Stream<Arguments> missingDirectoryConfiguration() {
        return Stream.of(
                Arguments.of("forge.scheduling.token=service-token", "forge.scheduling.iam-uri"),
                Arguments.of("forge.scheduling.iam-uri=http://localhost", "forge.scheduling.token"));
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void rejectsInvalidTenantDeclarationsBeforeSchedulingAnything(Class<?> taskType, String message) {
        var scheduler = new GlobalTaskRegistrationTest.RecordingScheduler();
        configured(scheduler).withBean(taskType).run(context -> {
            assertThat(context).hasFailed().getFailure().hasStackTraceContaining(message);
            assertThat(scheduler.delay).isNull();
            assertThat(scheduler.trigger).isNull();
        });
    }

    static Stream<Arguments> invalidDeclarations() {
        return Stream.of(
                Arguments.of(BlankName.class, "name"),
                Arguments.of(NoTiming.class, "exactly one"),
                Arguments.of(BothTimings.class, "exactly one"),
                Arguments.of(NegativeDelay.class, "positive"),
                Arguments.of(HasParameter.class, "no-argument void"),
                Arguments.of(HasReturnValue.class, "no-argument void"),
                Arguments.of(BothAnnotations.class, "only one task annotation"));
    }

    static class FixedTask {
        @TenantTask(name = "fixed", fixedDelay = "${task.delay}")
        void execute() {}
    }

    static class CronTask {
        @TenantTask(name = "cron", cron = "0 0 9 * * *")
        void execute() {}
    }

    static class BlankName {
        @TenantTask(name = " ", fixedDelay = "1s")
        void execute() {}
    }

    static class NoTiming {
        @TenantTask(name = "none")
        void execute() {}
    }

    static class BothTimings {
        @TenantTask(name = "both", cron = "0 * * * * *", fixedDelay = "1s")
        void execute() {}
    }

    static class NegativeDelay {
        @TenantTask(name = "negative", fixedDelay = "-1")
        void execute() {}
    }

    static class HasParameter {
        @TenantTask(name = "parameter", fixedDelay = "1s")
        void execute(String value) {}
    }

    static class HasReturnValue {
        @TenantTask(name = "return", fixedDelay = "1s")
        String execute() {
            return "value";
        }
    }

    static class BothAnnotations {
        @GlobalTask(name = "global", fixedDelay = "1s")
        @TenantTask(name = "tenant", fixedDelay = "1s")
        void execute() {}
    }

    private ApplicationContextRunner configured(GlobalTaskRegistrationTest.RecordingScheduler scheduler) {
        return runner(scheduler)
                .withPropertyValues(
                        "forge.scheduling.iam-uri=http://localhost", "forge.scheduling.token=service-token");
    }

    private ApplicationContextRunner runner(GlobalTaskRegistrationTest.RecordingScheduler scheduler) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchedulingAutoConfiguration.class))
                .withBean("forgeTaskScheduler", TaskScheduler.class, () -> scheduler)
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(new LettuceConnectionFactory()))
                .withBean(TenantStatusLookup.class, () -> id -> "active")
                .withPropertyValues("spring.application.name=tenant-registration-test");
    }
}
