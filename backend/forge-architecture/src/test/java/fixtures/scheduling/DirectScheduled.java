package fixtures.scheduling;

import org.springframework.scheduling.annotation.Scheduled;

public class DirectScheduled {
    @Scheduled(fixedDelay = 1000)
    void run() {}
}
