package io.saasforge.sdk.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ScheduledFuture;
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
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.SimpleTriggerContext;

class GlobalTaskRegistrationTest {
    @Test
    void registersFixedDelayWithPlaceholderWithoutRequiringBusinessZone() {
        var scheduler = new RecordingScheduler();
        runner(scheduler)
                .withBean(FixedTask.class)
                .withPropertyValues("task.delay=PT2S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(scheduler.delay).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Test
    void readsFixedDelayFromAnAlreadyBoundConfigurationBean() {
        var scheduler = new RecordingScheduler();
        runner(scheduler)
                .withBean(SpelTask.class)
                .withBean("interval", Interval.class, () -> new Interval(Duration.ofMinutes(1)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(scheduler.delay).isEqualTo(Duration.ofMinutes(1));
                });
    }

    record Interval(Duration scanInterval) {}

    static class SpelTask {
        @GlobalTask(name = "spel", fixedDelay = "#{@interval.scanInterval()}")
        void execute() {}
    }

    @Test
    void cronUsesBusinessZoneAndRequiresItAtStartup() {
        var scheduler = new RecordingScheduler();
        runner(scheduler)
                .withBean(CronTask.class)
                .withPropertyValues("forge.business-zone=Asia/Shanghai")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var triggerContext = new SimpleTriggerContext(
                            Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC));
                    assertThat(scheduler.trigger.nextExecution(triggerContext))
                            .isEqualTo(Instant.parse("2026-11-06T01:00:00Z"));
                });
        runner(new RecordingScheduler())
                .withBean(CronTask.class)
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasMessageContaining("forge.business-zone"));
    }

    static class CronTask {
        @GlobalTask(name = "cron", cron = "0 0 9 * * *")
        void execute() {}
    }

    @ParameterizedTest
    @MethodSource("invalidDeclarations")
    void rejectsInvalidDeclarationsBeforeSchedulingAnything(Class<?> taskType, String message) {
        var scheduler = new RecordingScheduler();
        runner(scheduler)
                .withBean(taskType)
                .withPropertyValues("forge.business-zone=Asia/Shanghai")
                .run(context -> {
                    assertThat(context).hasFailed().getFailure().hasMessageContaining(message);
                    assertThat(scheduler.delay).isNull();
                    assertThat(scheduler.trigger).isNull();
                });
    }

    static Stream<Arguments> invalidDeclarations() {
        return Stream.of(
                Arguments.of(BlankName.class, "name"),
                Arguments.of(DuplicateName.class, "duplicate"),
                Arguments.of(NoTiming.class, "exactly one"),
                Arguments.of(BothTimings.class, "exactly one"),
                Arguments.of(NegativeDelay.class, "positive"),
                Arguments.of(HasParameter.class, "no-argument void"),
                Arguments.of(HasReturnValue.class, "no-argument void"));
    }

    static class BlankName {
        @GlobalTask(name = " ", fixedDelay = "1s")
        void execute() {}
    }

    static class DuplicateName {
        @GlobalTask(name = "same", fixedDelay = "1s")
        void first() {}

        @GlobalTask(name = "same", fixedDelay = "1s")
        void second() {}
    }

    static class NoTiming {
        @GlobalTask(name = "none")
        void execute() {}
    }

    static class BothTimings {
        @GlobalTask(name = "both", cron = "0 * * * * *", fixedDelay = "1s")
        void execute() {}
    }

    static class NegativeDelay {
        @GlobalTask(name = "negative", fixedDelay = "-1")
        void execute() {}
    }

    static class HasParameter {
        @GlobalTask(name = "parameter", fixedDelay = "1s")
        void execute(String value) {}
    }

    static class HasReturnValue {
        @GlobalTask(name = "return", fixedDelay = "1s")
        String execute() {
            return "value";
        }
    }

    private ApplicationContextRunner runner(RecordingScheduler scheduler) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SchedulingAutoConfiguration.class))
                .withBean("forgeTaskScheduler", TaskScheduler.class, () -> scheduler)
                .withBean(StringRedisTemplate.class, () -> new StringRedisTemplate(new LettuceConnectionFactory()))
                .withPropertyValues("spring.application.name=registration-test");
    }

    static class FixedTask {
        @GlobalTask(name = "fixed", fixedDelay = "${task.delay}")
        void execute() {}
    }

    static class RecordingScheduler extends ThreadPoolTaskScheduler {
        Duration delay;
        Trigger trigger;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            this.trigger = trigger;
            return null;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            this.delay = delay;
            return null;
        }
    }
}
