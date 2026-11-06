package fixtures.scheduling;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

public class SchedulingExamples {
    @EnableScheduling
    public static class DirectEnable {}

    @Retention(RetentionPolicy.RUNTIME)
    @EnableScheduling
    public @interface SchedulingEnabled {}

    @SchedulingEnabled
    public static class ComposedEnable {}

    @Retention(RetentionPolicy.RUNTIME)
    @Scheduled(fixedDelay = 1000)
    public @interface EverySecond {}

    public static class ComposedScheduled {
        @EverySecond
        void run() {}
    }

    public static class RepeatedScheduled {
        @Scheduled(fixedDelay = 1000)
        @Scheduled(fixedDelay = 2000)
        void run() {}
    }

    public static class ContainerScheduled {
        @Schedules({@Scheduled(fixedDelay = 1000)})
        void run() {}
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Schedules({@Scheduled(fixedDelay = 1000)})
    public @interface RepeatedEverySecond {}

    public static class ComposedContainerScheduled {
        @RepeatedEverySecond
        void run() {}
    }

    public static class PlainTask {
        void run() {}
    }
}
