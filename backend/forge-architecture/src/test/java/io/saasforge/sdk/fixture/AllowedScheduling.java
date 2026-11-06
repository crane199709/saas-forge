package io.saasforge.sdk.fixture;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@EnableScheduling
public class AllowedScheduling {
    @Scheduled(fixedDelay = 1000)
    void run() {}
}
